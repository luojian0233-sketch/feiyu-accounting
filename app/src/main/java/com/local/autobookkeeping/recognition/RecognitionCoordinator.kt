package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.data.DuplicateRecognitionReceiptException
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.CATEGORY_UNSURE
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface AutoSavePolicy {
    suspend fun isEnabled(): Boolean

    companion object {
        fun failClosed(enabled: Flow<Boolean>): AutoSavePolicy = AutoSavePolicy {
            try {
                enabled.first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        }
    }
}

class RecognitionCoordinator(
    private val store: RecognitionStore,
    private val autoSavePolicy: AutoSavePolicy,
    private val classifier: MerchantClassifier = MerchantClassifier(store),
    private val diagnostics: RecognitionDiagnostics = InMemoryRecognitionDiagnostics(),
) {
    private val processMutex = Mutex()

    suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome = processMutex.withLock {
        processLocked(candidate)
    }

    private suspend fun processLocked(candidate: RecognitionCandidate): RecognitionOutcome {
        diagnostics.record(RecognitionDiagnostic.NONE)
        if (store.findReceipt(candidate.evidenceDigest) != null) {
            return RecognitionOutcome.Duplicate
        }

        val direction = candidate.direction
        if (direction != null) {
            val pendingMatches = store.findMatches(candidate.matchQuery(direction, PENDING_MATCH_SECONDS))
                .filter(Transaction::awaitingEnrichment)
            when {
                pendingMatches.size == 1 -> return enrichPending(pendingMatches.single(), candidate)
                pendingMatches.size > 1 -> return needsSelection(candidate, pendingMatches)
            }

            val confirmedMatches = store.findMatches(candidate.matchQuery(direction, CONFIRMED_MATCH_SECONDS))
                .filterNot(Transaction::pendingConfirmation)
                .filter { merchantsMatch(it.merchant, candidate.merchant) }
            when {
                confirmedMatches.size == 1 -> return attachConfirmed(confirmedMatches.single(), candidate)
                confirmedMatches.size > 1 -> return needsSelection(candidate, confirmedMatches)
            }
        }

        val classification = classifier.classify(candidate)
        val refundOrigin = candidate.refundOrigin()
        val booking = candidate.bookingDecision(classification)
        val transaction = candidate.toTransaction(classification, refundOrigin, booking)
        val receipt = candidate.toReceipt()

        return try {
            val id = store.create(transaction, receipt)
            saved(id, pending = booking.needsConfirmation)
        } catch (_: DuplicateRecognitionReceiptException) {
            RecognitionOutcome.Duplicate
        }
    }

    suspend fun resolve(choice: RecognitionChoice): RecognitionOutcome {
        val targetId = choice.matchTransactionId ?: return process(choice.candidate)
        return processMutex.withLock {
            resolveLocked(choice.candidate, targetId)
        }
    }

    private suspend fun resolveLocked(
        candidate: RecognitionCandidate,
        targetId: Long,
    ): RecognitionOutcome {
        diagnostics.record(RecognitionDiagnostic.NONE)
        if (store.findReceipt(candidate.evidenceDigest) != null) {
            return RecognitionOutcome.Duplicate
        }
        val direction = candidate.direction ?: return incomplete()

        val pendingTarget = store.findMatches(candidate.matchQuery(direction, PENDING_MATCH_SECONDS))
            .firstOrNull { it.awaitingEnrichment() && it.id == targetId }
        if (pendingTarget != null) {
            return enrichPending(pendingTarget, candidate)
        }

        val confirmedTarget = store.findMatches(candidate.matchQuery(direction, CONFIRMED_MATCH_SECONDS))
            .firstOrNull {
                !it.pendingConfirmation &&
                    it.id == targetId &&
                    merchantsMatch(it.merchant, candidate.merchant)
            } ?: return incomplete()
        return enrichConfirmed(confirmedTarget, candidate)
    }

    private suspend fun enrichPending(
        existing: Transaction,
        candidate: RecognitionCandidate,
    ): RecognitionOutcome {
        val combined = candidate.copy(
            merchant = candidate.merchant.preferNonBlank(existing.merchant),
            account = candidate.account.preferNonBlank(existing.account),
            confidence = max(existing.recognitionConfidence, candidate.confidence),
        )
        val classification = classifier.classify(combined)
        val refundOrigin = combined.refundOrigin()
        val booking = combined.bookingDecision(classification)
        val updated = existing.copy(
            amountMinor = combined.amountMinor,
            direction = combined.direction ?: existing.direction,
            category = booking.category,
            occurredAt = combined.occurredAt,
            merchant = combined.merchant.orEmpty().trim(),
            account = classification.account.orEmpty().trim(),
            pendingConfirmation = booking.needsConfirmation,
            refundOfId = existing.refundOfId ?: refundOrigin?.id,
            platform = combined.platform,
            eventKind = combined.eventKind,
            recognitionConfidence = combined.confidence,
        )

        return try {
            store.update(updated, candidate.toReceipt())
            saved(existing.id, pending = booking.needsConfirmation)
        } catch (_: DuplicateRecognitionReceiptException) {
            RecognitionOutcome.Duplicate
        }
    }

    private suspend fun enrichConfirmed(
        existing: Transaction,
        candidate: RecognitionCandidate,
    ): RecognitionOutcome {
        val updated = existing.copy(
            merchant = candidate.merchant.preferNonBlank(existing.merchant).orEmpty().trim(),
            account = candidate.account.preferNonBlank(existing.account).orEmpty().trim(),
            recognitionConfidence = max(existing.recognitionConfidence, candidate.confidence),
        )
        return try {
            store.update(updated, candidate.toReceipt())
            RecognitionOutcome.Duplicate
        } catch (_: DuplicateRecognitionReceiptException) {
            RecognitionOutcome.Duplicate
        }
    }

    private suspend fun attachConfirmed(
        existing: Transaction,
        candidate: RecognitionCandidate,
    ): RecognitionOutcome = try {
        store.attachReceipt(existing.id, candidate.toReceipt())
        RecognitionOutcome.Duplicate
    } catch (_: DuplicateRecognitionReceiptException) {
        RecognitionOutcome.Duplicate
    }

    private fun needsSelection(
        candidate: RecognitionCandidate,
        matches: List<Transaction>,
    ): RecognitionOutcome.NeedsSelection {
        diagnostics.record(RecognitionDiagnostic.MULTIPLE_CANDIDATES)
        return RecognitionOutcome.NeedsSelection(
            matches.take(MAX_REVIEW_OPTIONS).map {
                RecognitionChoice(candidate, matchTransactionId = it.id)
            },
        )
    }

    /**
     * 一笔识别结果该怎么落库：分得清就归好类，分不清就挂到「待分类」照常入账。
     *
     * 以前只有「全自动」和「待核对」两档，分类判不出来就把整笔拦下。可微信/支付宝的
     * 支付通知只给「已支付¥4.00」这一行，商户名系统层面就没有，于是每一笔都成了待核对，
     * 今日支出一直是 0。金额才是通知里最可靠的一项，方向和事件类型也都认得出来，
     * 到这一步就够记账了，分类缺失不该阻断入账。
     */
    private suspend fun RecognitionCandidate.bookingDecision(
        classification: Classification,
    ): BookingDecision {
        // 自动保存是用户自己的开关：关掉就意味着「别替我记账」，这里必须先让路。
        // 开关开着的前提下，金额、方向、事件类型都认得出就够入账了。
        val bookable = autoSavePolicy.isEnabled() &&
            direction != null &&
            eventKind != TransactionEventKind.UNKNOWN &&
            amountMinor > 0
        if (!bookable) {
            return BookingDecision(CATEGORY_UNSURE, needsConfirmation = true)
        }
        val categorized = hasStableIdentity() &&
            classification.category != null &&
            confidence >= AUTO_SAVE_CONFIDENCE
        return BookingDecision(
            category = if (categorized) checkNotNull(classification.category) else CATEGORY_UNSURE,
            needsConfirmation = false,
        )
    }

    private suspend fun RecognitionCandidate.refundOrigin(): Transaction? =
        if (eventKind == TransactionEventKind.REFUND) store.findRefundOrigin(this) else null

    private fun saved(id: Long, pending: Boolean): RecognitionOutcome.Saved {
        diagnostics.record(
            if (pending) RecognitionDiagnostic.NEEDS_CONFIRMATION else RecognitionDiagnostic.SAVED,
        )
        return RecognitionOutcome.Saved(id, pending)
    }

    private fun incomplete(): RecognitionOutcome.Rejected {
        diagnostics.record(RecognitionDiagnostic.INCOMPLETE_FIELDS)
        return RecognitionOutcome.Rejected(RecognitionRejectReason.INCOMPLETE_FIELDS)
    }

    private companion object {
        const val AUTO_SAVE_CONFIDENCE = 80

        /**
         * 通知先到、页面识别后补商户，中间可能隔几小时（睡前才翻账单很常见）。
         * 窗口放到一天，命中与否再靠「同平台同方向同金额只允许唯一候选」兜底：
         * 一旦有歧义就不补，宁可留待分类，也不把商户安到错的记录上。
         */
        const val PENDING_MATCH_SECONDS = 24 * 60 * 60L
        const val CONFIRMED_MATCH_SECONDS = 2 * 60L
    }
}

/**
 * 落库时的两个决定：这笔归到哪个分类，以及要不要拦住它等人工确认。
 * 两者已经解耦——分类判不出来不代表金额不可信。
 */
private data class BookingDecision(
    val category: String,
    val needsConfirmation: Boolean,
)

private fun RecognitionCandidate.matchQuery(
    knownDirection: Direction,
    radiusSeconds: Long,
) = RecognitionMatchQuery(
    platform = platform,
    direction = knownDirection,
    amountMinor = amountMinor,
    eventKind = eventKind,
    from = occurredAt.minusSeconds(radiusSeconds),
    to = occurredAt.plusSeconds(radiusSeconds),
)

private fun RecognitionCandidate.toTransaction(
    classification: Classification,
    refundOrigin: Transaction?,
    booking: BookingDecision,
) = Transaction(
    amountMinor = amountMinor,
    direction = direction ?: Direction.EXPENSE,
    category = booking.category,
    occurredAt = occurredAt,
    merchant = merchant.orEmpty().trim(),
    account = classification.account.orEmpty().trim(),
    source = channel.toSource(),
    pendingConfirmation = booking.needsConfirmation,
    internalTransfer = false,
    refundOfId = refundOrigin?.id,
    platform = platform,
    eventKind = eventKind,
    recognitionConfidence = confidence,
)

private fun RecognitionCandidate.toReceipt() = RecognitionReceipt(
    channel = channel,
    platform = platform,
    eventKind = eventKind,
    observedAt = occurredAt,
    digest = evidenceDigest,
)

private fun RecognitionCandidate.hasStableIdentity(): Boolean =
    !merchant.isNullOrBlank() || eventKind in STABLE_EVENT_KINDS

/**
 * 还能被后续证据补全的记录：没入账的待核对，以及已入账但分类还空着的待分类。
 *
 * 「通知先到、页面识别后到」全靠这个窗口把商户和分类补齐——记录一旦入账就不再是
 * pending，但分类还挂着「待分类」时仍然允许被同一笔的页面识别覆盖。
 */
private fun Transaction.awaitingEnrichment(): Boolean =
    pendingConfirmation || category == CATEGORY_UNSURE

private fun merchantsMatch(existing: String, candidate: String?): Boolean {
    val left = existing.takeIf(String::isNotBlank)?.let(::normalizeMerchant)
    val right = candidate?.takeIf(String::isNotBlank)?.let(::normalizeMerchant)
    return left == null || right == null || left == right
}

private fun String?.preferNonBlank(fallback: String): String? =
    this?.takeIf(String::isNotBlank) ?: fallback.takeIf(String::isNotBlank)

private fun CaptureChannel.toSource() = when (this) {
    CaptureChannel.NOTIFICATION -> Source.NOTIFICATION
    CaptureChannel.ACCESSIBILITY -> Source.PAGE_RECOGNITION
    CaptureChannel.SCREENSHOT -> Source.SCREENSHOT
}

private val STABLE_EVENT_KINDS = setOf(
    TransactionEventKind.REFUND,
    TransactionEventKind.TRANSFER,
    TransactionEventKind.RED_PACKET,
)
