package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.data.DuplicateRecognitionReceiptException
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface AutoSavePolicy {
    suspend fun isEnabled(): Boolean
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
                .filter(Transaction::pendingConfirmation)
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
        val autoSave = shouldAutoSave(candidate, classification)
        val transaction = candidate.toTransaction(classification, refundOrigin, autoSave)
        val receipt = candidate.toReceipt()

        return try {
            val id = store.create(transaction, receipt)
            saved(id, pending = !autoSave)
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
            .firstOrNull { it.pendingConfirmation && it.id == targetId }
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
        val autoSave = shouldAutoSave(combined, classification)
        val updated = existing.copy(
            amountMinor = combined.amountMinor,
            direction = combined.direction ?: existing.direction,
            category = if (autoSave) classification.category!! else PENDING_CATEGORY,
            occurredAt = combined.occurredAt,
            merchant = combined.merchant.orEmpty().trim(),
            account = classification.account.orEmpty().trim(),
            pendingConfirmation = !autoSave,
            refundOfId = existing.refundOfId ?: refundOrigin?.id,
            platform = combined.platform,
            eventKind = combined.eventKind,
            recognitionConfidence = combined.confidence,
        )

        return try {
            store.update(updated, candidate.toReceipt())
            saved(existing.id, pending = !autoSave)
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
            matches.map { RecognitionChoice(candidate, matchTransactionId = it.id) },
        )
    }

    private suspend fun shouldAutoSave(
        candidate: RecognitionCandidate,
        classification: Classification,
    ): Boolean = autoSavePolicy.isEnabled() &&
        candidate.direction != null &&
        candidate.eventKind != TransactionEventKind.UNKNOWN &&
        candidate.hasStableIdentity() &&
        classification.category != null &&
        candidate.confidence >= AUTO_SAVE_CONFIDENCE

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
        const val PENDING_MATCH_SECONDS = 15 * 60L
        const val CONFIRMED_MATCH_SECONDS = 2 * 60L
        const val PENDING_CATEGORY = "待确认"
    }
}

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
    autoSave: Boolean,
) = Transaction(
    amountMinor = amountMinor,
    direction = direction ?: Direction.EXPENSE,
    category = if (autoSave) classification.category!! else "待确认",
    occurredAt = occurredAt,
    merchant = merchant.orEmpty().trim(),
    account = classification.account.orEmpty().trim(),
    source = channel.toSource(),
    pendingConfirmation = !autoSave,
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
