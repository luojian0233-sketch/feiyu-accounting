package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.MerchantRuleMatch
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.AutoSavePolicy
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationCandidateMapperTest {
    @Test
    fun `payment candidate maps notification platform event and redacted evidence`() {
        val mapped = paymentCandidate(
            sourcePackage = NotificationRouter.ALIPAY_PACKAGE,
            eventKind = PaymentEventKind.RED_PACKET,
            direction = Direction.INCOME,
        ).toRecognitionCandidate()

        assertEquals(PaymentPlatform.ALIPAY, mapped.platform)
        assertEquals(CaptureChannel.NOTIFICATION, mapped.channel)
        assertEquals(TransactionEventKind.RED_PACKET, mapped.eventKind)
        assertEquals(1250L, mapped.amountMinor)
        assertEquals(Direction.INCOME, mapped.direction)
        assertEquals("中国电信", mapped.merchant)
        assertNull(mapped.account)
        assertEquals(NOW, mapped.occurredAt)
        assertEquals(90, mapped.confidence)
        assertEquals("a".repeat(64), mapped.evidenceDigest)
    }

    @Test
    fun `all notification event kinds map without losing semantics`() {
        val cases = mapOf(
            PaymentEventKind.PAYMENT to TransactionEventKind.PAYMENT,
            PaymentEventKind.DEBIT to TransactionEventKind.PAYMENT,
            PaymentEventKind.RECEIPT to TransactionEventKind.RECEIPT,
            PaymentEventKind.CREDIT to TransactionEventKind.RECEIPT,
            PaymentEventKind.REFUND to TransactionEventKind.REFUND,
            PaymentEventKind.TRANSFER to TransactionEventKind.TRANSFER,
            PaymentEventKind.RED_PACKET to TransactionEventKind.RED_PACKET,
            PaymentEventKind.AMBIGUOUS to TransactionEventKind.UNKNOWN,
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, paymentCandidate(eventKind = source).toRecognitionCandidate().eventKind)
        }
    }

    @Test
    fun `real notification saves and deduplicates only through recognition coordinator`() = runTest {
        val store = RecordingRecognitionStore()
        val coordinator = RecognitionCoordinator(store, AutoSavePolicy { true })
        val rawBody = "支付成功 ￥12.50，商户：中国电信；原始正文秘密"
        val candidate = requireNotNull(
            WechatParser.parse(
                NotificationText("微信支付", rawBody, NotificationRouter.WECHAT_PACKAGE, NOW),
            ),
        ).toRecognitionCandidate()

        val first = coordinator.process(candidate)
        val duplicate = coordinator.process(candidate)

        assertEquals(RecognitionOutcome.Saved(1L, pending = false), first)
        assertEquals(RecognitionOutcome.Duplicate, duplicate)
        assertEquals(1, store.transactions.size)
        assertEquals(1, store.receipts.size)
        assertEquals(PaymentPlatform.WECHAT, store.transactions.single().platform)
        assertEquals(CaptureChannel.NOTIFICATION, store.receipts.single().channel)
        assertEquals(candidate.evidenceDigest, store.receipts.single().digest)
        val saved = store.transactions.single()
        assertEquals("中国电信", saved.merchant)
        assertTrue(
            listOf(saved.merchant, saved.account, saved.category, saved.note, saved.dedupeKey.orEmpty())
                .none { it.contains("原始正文秘密") },
        )
        assertFalse(saved.toString().contains(rawBody))
        assertTrue(saved.dedupeKey.isNullOrBlank())
    }

    @Test
    fun `opposite transfer and red packet directions have distinct digests and both save`() = runTest {
        val cases = listOf(
            "transfer" to listOf(
                "转账到账 人民币12.50",
                "转账已支付 人民币12.50",
            ),
            "red packet" to listOf(
                "收到红包 人民币12.50",
                "红包已支付 人民币12.50",
            ),
        )

        cases.forEach { (label, bodies) ->
            val candidates = bodies.map { body ->
                requireNotNull(
                    AlipayParser.parse(
                        NotificationText("支付宝", body, NotificationRouter.ALIPAY_PACKAGE, NOW),
                    ),
                ).toRecognitionCandidate()
            }
            val store = RecordingRecognitionStore()
            val coordinator = RecognitionCoordinator(store, AutoSavePolicy { true })

            assertNotEquals(candidates[0].evidenceDigest, candidates[1].evidenceDigest, label)
            val outcomes = candidates.map { coordinator.process(it) }

            assertTrue(outcomes.all { it is RecognitionOutcome.Saved }, label)
            assertEquals(2, store.transactions.size, label)
            assertEquals(2, store.receipts.size, label)
            assertEquals(setOf(Direction.INCOME, Direction.EXPENSE), store.transactions.map { it.direction }.toSet())
        }
    }

    private fun paymentCandidate(
        sourcePackage: String = NotificationRouter.WECHAT_PACKAGE,
        eventKind: PaymentEventKind = PaymentEventKind.PAYMENT,
        direction: Direction? = Direction.EXPENSE,
    ) = PaymentCandidate(
        amountMinor = 1250L,
        direction = direction,
        merchant = "中国电信",
        occurredAt = NOW,
        sourcePackage = sourcePackage,
        confidence = 90,
        redactedDigest = "a".repeat(64),
        dedupeKey = "b".repeat(64),
        eventKind = eventKind,
    )

    private class RecordingRecognitionStore : RecognitionStore {
        val transactions = mutableListOf<Transaction>()
        val receipts = mutableListOf<RecognitionReceipt>()

        override suspend fun findReceipt(digest: String): RecognitionReceipt? =
            receipts.singleOrNull { it.digest == digest }

        override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> = emptyList()

        override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long {
            val id = transactions.size + 1L
            transactions += transaction.copy(id = id)
            receipts += receipt.copy(transactionId = id)
            return id
        }

        override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) = Unit

        override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) = Unit

        override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? = null

        override suspend fun isCategoryVisible(name: String): Boolean = name == "通讯"

        override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? = null

        override suspend fun rememberMerchantRule(
            merchant: String,
            category: String,
            account: String?,
        ) = Unit

        override suspend fun confirmAndRemember(transaction: Transaction): Long = transaction.id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-29T10:00:00Z")
    }
}
