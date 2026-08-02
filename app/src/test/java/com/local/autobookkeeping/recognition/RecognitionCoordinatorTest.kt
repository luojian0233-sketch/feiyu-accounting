package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.data.DuplicateRecognitionReceiptException
import com.local.autobookkeeping.data.MerchantRuleMatch
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecognitionCoordinatorTest {
    private val store = CoordinatorStore()
    private val autoSavePolicy = MutableAutoSavePolicy()
    private val diagnostics = InMemoryRecognitionDiagnostics()
    private val coordinator = RecognitionCoordinator(
        store = store,
        autoSavePolicy = autoSavePolicy,
        diagnostics = diagnostics,
    )

    @Test
    fun `known telecom auto saves and duplicate digest is ignored`() = runTest {
        store.visibleCategories += "通讯"

        val first = coordinator.process(telecomCandidate(channel = CaptureChannel.NOTIFICATION))
        val duplicate = coordinator.process(telecomCandidate(channel = CaptureChannel.ACCESSIBILITY))

        assertEquals(RecognitionOutcome.Saved(1, pending = false), first)
        assertEquals(RecognitionOutcome.Duplicate, duplicate)
        assertEquals(1, store.transactions.size)
        assertEquals(1, store.receipts.size)
    }

    @Test
    fun `concurrent cross channel candidates still create only one transaction`() = runTest {
        store.visibleCategories += "通讯"
        store.yieldOnReads = true

        val outcomes = listOf(
            async {
                coordinator.process(
                    telecomCandidate(channel = CaptureChannel.NOTIFICATION, digestChar = 'a'),
                )
            },
            async {
                coordinator.process(
                    telecomCandidate(channel = CaptureChannel.ACCESSIBILITY, digestChar = 'b'),
                )
            },
        ).awaitAll()

        assertEquals(1, outcomes.count { it is RecognitionOutcome.Saved })
        assertEquals(1, outcomes.count { it == RecognitionOutcome.Duplicate })
        assertEquals(1, store.transactions.size)
        assertEquals(2, store.receipts.size)
    }

    @Test
    fun `notification then page enriches one pending transaction`() = runTest {
        store.visibleCategories += "通讯"

        coordinator.process(notificationCandidate(amount = 100, merchant = null))
        val result = coordinator.process(pageCandidate(amount = 100, merchant = "中国电信"))

        assertEquals(RecognitionOutcome.Saved(1, pending = false), result)
        assertEquals(1, store.transactions.size)
        assertEquals("中国电信", store.transactions.single().merchant)
        assertEquals("通讯", store.transactions.single().category)
        assertFalse(store.transactions.single().pendingConfirmation)
        assertEquals(2, store.receipts.size)
        assertEquals(listOf(WriteKind.CREATE, WriteKind.UPDATE), store.writes)
    }

    @Test
    fun `pending matching includes exact fifteen minute boundary`() = runTest {
        store.visibleCategories += "通讯"
        store.seed(
            transaction(
                id = 7,
                occurredAt = NOW.minusSeconds(15 * 60),
                pending = true,
            ),
        )

        val result = coordinator.process(pageCandidate())

        assertEquals(RecognitionOutcome.Saved(7, pending = false), result)
        assertEquals(1, store.transactions.size)
        assertEquals(7, store.receipts.single().transactionId)
    }

    @Test
    fun `pending matching does not cross fifteen minute boundary`() = runTest {
        store.visibleCategories += "通讯"
        store.seed(
            transaction(
                id = 7,
                occurredAt = NOW.minusSeconds(15 * 60).minusMillis(1),
                pending = true,
            ),
        )

        val result = coordinator.process(pageCandidate())

        assertEquals(RecognitionOutcome.Saved(8, pending = false), result)
        assertEquals(2, store.transactions.size)
        assertEquals(8, store.receipts.single().transactionId)
    }

    @Test
    fun `two same amount matches never auto merge`() = runTest {
        store.seed(
            transaction(id = 11, pending = true),
            transaction(id = 12, occurredAt = NOW.minusSeconds(60), pending = true),
        )

        val result = coordinator.process(pageCandidate(merchant = null))

        assertEquals(
            RecognitionOutcome.NeedsSelection(
                listOf(
                    RecognitionChoice(pageCandidate(merchant = null), 11),
                    RecognitionChoice(pageCandidate(merchant = null), 12),
                ),
            ),
            result,
        )
        assertTrue(store.receipts.isEmpty())
        assertTrue(store.writes.isEmpty())
        assertEquals(RecognitionDiagnostic.MULTIPLE_CANDIDATES, diagnostics.state.value)
    }

    @Test
    fun `confirmed match in two minutes attaches cross channel receipt`() = runTest {
        store.seed(transaction(id = 21, pending = false, merchant = " 中国电信 "))

        val result = coordinator.process(pageCandidate(merchant = "中国电信"))

        assertEquals(RecognitionOutcome.Duplicate, result)
        assertEquals(1, store.transactions.size)
        assertEquals(21, store.receipts.single().transactionId)
        assertEquals(listOf(WriteKind.ATTACH), store.writes)
    }

    @Test
    fun `confirmed merchants must normalize equal before receipt attaches`() = runTest {
        store.visibleCategories += "通讯"
        store.seed(transaction(id = 21, pending = false, merchant = "便利店"))

        val result = coordinator.process(pageCandidate(merchant = "中国电信"))

        assertEquals(RecognitionOutcome.Saved(22, pending = false), result)
        assertEquals(2, store.transactions.size)
        assertEquals(22, store.receipts.single().transactionId)
        assertEquals(listOf(WriteKind.CREATE), store.writes)
    }

    @Test
    fun `confirmed matching does not cross two minute boundary`() = runTest {
        store.visibleCategories += "通讯"
        store.seed(
            transaction(
                id = 21,
                occurredAt = NOW.minusSeconds(2 * 60).minusMillis(1),
                pending = false,
            ),
        )

        val result = coordinator.process(pageCandidate())

        assertEquals(RecognitionOutcome.Saved(22, pending = false), result)
        assertEquals(listOf(WriteKind.CREATE), store.writes)
    }

    @Test
    fun `multiple confirmed candidates require selection instead of guessing`() = runTest {
        store.seed(
            transaction(id = 31, pending = false),
            transaction(id = 32, occurredAt = NOW.minusSeconds(30), pending = false),
        )

        val candidate = pageCandidate()
        val result = coordinator.process(candidate)

        assertEquals(
            RecognitionOutcome.NeedsSelection(
                listOf(RecognitionChoice(candidate, 31), RecognitionChoice(candidate, 32)),
            ),
            result,
        )
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `disabled automatic save always creates a reviewable pending row`() = runTest {
        store.visibleCategories += "通讯"
        autoSavePolicy.enabled = false

        val result = coordinator.process(telecomCandidate())

        assertEquals(RecognitionOutcome.Saved(1, pending = true), result)
        assertEquals("待确认", store.transactions.single().category)
        assertTrue(store.transactions.single().pendingConfirmation)
        assertEquals(RecognitionDiagnostic.NEEDS_CONFIRMATION, diagnostics.state.value)
    }

    @Test
    fun `automatic save requires direction known event visible category and threshold`() = runTest {
        store.visibleCategories += "通讯"
        val candidates = listOf(
            telecomCandidate(amount = 100, direction = null, digestChar = 'b'),
            telecomCandidate(amount = 200, eventKind = TransactionEventKind.UNKNOWN, digestChar = 'c'),
            telecomCandidate(amount = 300, confidence = 79, digestChar = 'd'),
            telecomCandidate(amount = 400, merchant = "便利店", digestChar = 'e'),
        )

        val results = candidates.map { coordinator.process(it) }

        assertEquals(
            listOf(
                RecognitionOutcome.Saved(1, pending = true),
                RecognitionOutcome.Saved(2, pending = true),
                RecognitionOutcome.Saved(3, pending = true),
                RecognitionOutcome.Saved(4, pending = true),
            ),
            results,
        )
        assertTrue(store.transactions.all { it.pendingConfirmation && it.category == "待确认" })
        assertEquals(Direction.EXPENSE, store.transactions.first().direction)
    }

    @Test
    fun `confidence exactly eighty can auto save`() = runTest {
        store.visibleCategories += "通讯"

        assertEquals(
            RecognitionOutcome.Saved(1, pending = false),
            coordinator.process(telecomCandidate(confidence = 80)),
        )
    }

    @Test
    fun `refund category and unique origin are persisted`() = runTest {
        store.visibleCategories += "退款"
        store.refundOrigin = transaction(
            id = 41,
            direction = Direction.EXPENSE,
            eventKind = TransactionEventKind.PAYMENT,
            category = "通讯",
            pending = false,
        )
        val refund = telecomCandidate(
            direction = Direction.INCOME,
            merchant = null,
            eventKind = TransactionEventKind.REFUND,
            digestChar = 'e',
        )

        val result = coordinator.process(refund)

        assertEquals(RecognitionOutcome.Saved(1, pending = false), result)
        assertEquals("退款", store.transactions.single().category)
        assertEquals(41, store.transactions.single().refundOfId)
        assertEquals(1, store.refundQueries)
    }

    @Test
    fun `new rows map each channel source and never infer internal transfer`() = runTest {
        store.visibleCategories += "通讯"
        val candidates = listOf(
            telecomCandidate(channel = CaptureChannel.NOTIFICATION, amount = 100, digestChar = 'f'),
            telecomCandidate(channel = CaptureChannel.ACCESSIBILITY, amount = 200, digestChar = '1'),
            telecomCandidate(channel = CaptureChannel.SCREENSHOT, amount = 300, digestChar = '2'),
        )

        candidates.forEach { coordinator.process(it) }

        assertEquals(
            listOf(Source.NOTIFICATION, Source.PAGE_RECOGNITION, Source.SCREENSHOT),
            store.transactions.map { it.source },
        )
        assertTrue(store.transactions.none { it.internalTransfer })
    }

    @Test
    fun `merchant rule category and account are applied to saved transaction`() = runTest {
        store.visibleCategories += "日用"
        store.rule = MerchantRuleMatch("中国电信", "日用", "花呗", priority = 100)

        coordinator.process(telecomCandidate())

        assertEquals("日用", store.transactions.single().category)
        assertEquals("花呗", store.transactions.single().account)
    }

    @Test
    fun `choice without target delegates to normal processing`() = runTest {
        store.visibleCategories += "通讯"
        val candidate = pageCandidate()

        val result = coordinator.resolve(RecognitionChoice(candidate))

        assertEquals(RecognitionOutcome.Saved(1, pending = false), result)
        assertEquals(1, store.transactions.size)
    }

    @Test
    fun `selected pending target is revalidated enriched and receipted atomically`() = runTest {
        store.visibleCategories += "通讯"
        store.seed(transaction(id = 51, pending = true, merchant = ""))
        val candidate = pageCandidate()

        val result = coordinator.resolve(RecognitionChoice(candidate, matchTransactionId = 51))

        assertEquals(RecognitionOutcome.Saved(51, pending = false), result)
        assertEquals("中国电信", store.transactions.single().merchant)
        assertEquals(51, store.receipts.single().transactionId)
        assertEquals(listOf(WriteKind.UPDATE), store.writes)
    }

    @Test
    fun `missing or changed selected target rejects without creating a row`() = runTest {
        store.seed(
            transaction(
                id = 61,
                amount = 200,
                pending = true,
            ),
        )

        val result = coordinator.resolve(RecognitionChoice(pageCandidate(amount = 100), 61))

        assertEquals(
            RecognitionOutcome.Rejected(RecognitionRejectReason.INCOMPLETE_FIELDS),
            result,
        )
        assertEquals(1, store.transactions.size)
        assertTrue(store.receipts.isEmpty())
        assertTrue(store.writes.isEmpty())
        assertEquals(RecognitionDiagnostic.INCOMPLETE_FIELDS, diagnostics.state.value)
    }

    @Test
    fun `selected confirmed target retains confirmation and category while enriching`() = runTest {
        store.seed(
            transaction(
                id = 71,
                pending = false,
                merchant = "",
                category = "日用",
            ),
        )

        val result = coordinator.resolve(RecognitionChoice(pageCandidate(), 71))

        assertEquals(RecognitionOutcome.Duplicate, result)
        assertEquals("日用", store.transactions.single().category)
        assertFalse(store.transactions.single().pendingConfirmation)
        assertEquals("中国电信", store.transactions.single().merchant)
        assertEquals(listOf(WriteKind.UPDATE), store.writes)
    }

    @Test
    fun `atomic duplicate receipt failure is returned as duplicate without a partial row`() = runTest {
        store.visibleCategories += "通讯"
        store.failNextWriteAsDuplicate = true

        val result = coordinator.process(telecomCandidate())

        assertEquals(RecognitionOutcome.Duplicate, result)
        assertTrue(store.transactions.isEmpty())
        assertTrue(store.receipts.isEmpty())
        assertEquals(listOf(WriteKind.CREATE), store.writes)
    }

    @Test
    fun `diagnostics exposes only redacted enum state`() {
        val values = RecognitionDiagnostic.entries

        values.forEach(diagnostics::record)

        assertEquals(RecognitionDiagnostic.OCR_FAILED, diagnostics.state.value)
        assertEquals(RecognitionDiagnostic::class, diagnostics.state.value::class)
    }

    private fun notificationCandidate(amount: Long, merchant: String?) = telecomCandidate(
        channel = CaptureChannel.NOTIFICATION,
        amount = amount,
        merchant = merchant,
        confidence = 60,
        digestChar = 'a',
    )

    private fun pageCandidate(amount: Long = 100, merchant: String? = "中国电信") = telecomCandidate(
        channel = CaptureChannel.ACCESSIBILITY,
        amount = amount,
        merchant = merchant,
        digestChar = 'b',
    )

    private fun telecomCandidate(
        channel: CaptureChannel = CaptureChannel.NOTIFICATION,
        amount: Long = 100,
        direction: Direction? = Direction.EXPENSE,
        merchant: String? = "中国电信",
        eventKind: TransactionEventKind = TransactionEventKind.PAYMENT,
        confidence: Int = 95,
        digestChar: Char = 'a',
    ) = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = channel,
        eventKind = eventKind,
        amountMinor = amount,
        direction = direction,
        merchant = merchant,
        account = "零钱",
        occurredAt = NOW,
        confidence = confidence,
        evidenceDigest = digestChar.toString().repeat(64),
    )

    private fun transaction(
        id: Long,
        amount: Long = 100,
        direction: Direction = Direction.EXPENSE,
        occurredAt: Instant = NOW,
        merchant: String = "中国电信",
        category: String = "待确认",
        pending: Boolean,
        eventKind: TransactionEventKind = TransactionEventKind.PAYMENT,
    ) = Transaction(
        id = id,
        amountMinor = amount,
        direction = direction,
        category = category,
        occurredAt = occurredAt,
        merchant = merchant,
        account = "零钱",
        source = Source.NOTIFICATION,
        pendingConfirmation = pending,
        platform = PaymentPlatform.WECHAT,
        eventKind = eventKind,
        recognitionConfidence = 60,
    )

    private class MutableAutoSavePolicy(var enabled: Boolean = true) : AutoSavePolicy {
        override suspend fun isEnabled(): Boolean = enabled
    }

    private enum class WriteKind { CREATE, UPDATE, ATTACH }

    private class CoordinatorStore : RecognitionStore {
        val transactions = mutableListOf<Transaction>()
        val receipts = mutableListOf<RecognitionReceipt>()
        val visibleCategories = mutableSetOf<String>()
        val writes = mutableListOf<WriteKind>()
        var rule: MerchantRuleMatch? = null
        var refundOrigin: Transaction? = null
        var refundQueries: Int = 0
        var failNextWriteAsDuplicate: Boolean = false
        var yieldOnReads: Boolean = false

        fun seed(vararg values: Transaction) {
            transactions += values
        }

        override suspend fun findReceipt(digest: String): RecognitionReceipt? {
            val result = receipts.singleOrNull { it.digest == digest }
            if (yieldOnReads) yield()
            return result
        }

        override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> {
            val result = transactions.filter { transaction ->
                transaction.platform == query.platform &&
                    transaction.direction == query.direction &&
                    transaction.amountMinor == query.amountMinor &&
                    transaction.eventKind == query.eventKind &&
                    !transaction.occurredAt.isBefore(query.from) &&
                    !transaction.occurredAt.isAfter(query.to)
            }
            if (yieldOnReads) yield()
            return result
        }

        override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long {
            writes += WriteKind.CREATE
            failIfDuplicate(receipt)
            val id = (transactions.maxOfOrNull { it.id } ?: 0L) + 1
            transactions += transaction.copy(id = id)
            receipts += receipt.copy(transactionId = id)
            return id
        }

        override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) {
            writes += WriteKind.UPDATE
            failIfDuplicate(receipt)
            val index = transactions.indexOfFirst { it.id == transaction.id }
            require(index >= 0)
            transactions[index] = transaction
            receipts += receipt.copy(transactionId = transaction.id)
        }

        override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) {
            writes += WriteKind.ATTACH
            failIfDuplicate(receipt)
            require(transactions.any { it.id == transactionId })
            receipts += receipt.copy(transactionId = transactionId)
        }

        override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? =
            rule?.takeIf { it.merchantPattern == normalizedMerchant }

        override suspend fun isCategoryVisible(name: String): Boolean = name in visibleCategories

        override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? {
            refundQueries += 1
            return refundOrigin
        }

        override suspend fun rememberMerchantRule(merchant: String, category: String, account: String?) = Unit

        override suspend fun confirmAndRemember(transaction: Transaction): Long = transaction.id

        private fun failIfDuplicate(receipt: RecognitionReceipt) {
            if (failNextWriteAsDuplicate) {
                failNextWriteAsDuplicate = false
                throw DuplicateRecognitionReceiptException(receipt.digest)
            }
            if (receipts.any { it.digest == receipt.digest }) {
                throw DuplicateRecognitionReceiptException(receipt.digest)
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-29T10:00:00Z")
    }
}
