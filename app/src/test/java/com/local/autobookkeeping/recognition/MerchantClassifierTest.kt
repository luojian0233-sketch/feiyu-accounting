package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.data.MerchantRuleMatch
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MerchantClassifierTest {
    private val store = ClassifierStore()
    private val classifier = MerchantClassifier(store)

    @Test
    fun `user rule overrides built in telecom classification`() = runTest {
        store.visibleCategories += setOf("日用", "通讯")
        store.rule = MerchantRuleMatch("中国电信", "日用", null, priority = 100)

        assertEquals(Classification("日用", "零钱"), classifier.classify(telecomCandidate()))
        assertEquals("中国电信", store.lastRuleLookup)
    }

    @Test
    fun `user rule account overrides recognized account`() = runTest {
        store.visibleCategories += "日用"
        store.rule = MerchantRuleMatch("中国电信", "日用", "招商银行 (1234)", priority = 100)

        assertEquals(
            Classification("日用", "招商银行 (1234)"),
            classifier.classify(telecomCandidate()),
        )
    }

    @Test
    fun `hidden user category falls back to visible built in category`() = runTest {
        store.visibleCategories += "通讯"
        store.rule = MerchantRuleMatch("中国电信", "日用", "花呗", priority = 100)

        assertEquals(Classification("通讯", "零钱"), classifier.classify(telecomCandidate()))
    }

    @Test
    fun `known telecom merchants normalize and classify deterministically`() = runTest {
        store.visibleCategories += "通讯"
        val merchants = listOf(" 中国电信 ", "中国移动", "中国联通")

        assertEquals(
            listOf("通讯", "通讯", "通讯"),
            merchants.map { classifier.classify(telecomCandidate(merchant = it)).category },
        )
    }

    @Test
    fun `event categories are used only when visible`() = runTest {
        store.visibleCategories += setOf("退款", "红包")

        assertEquals("退款", classifier.classify(candidate(TransactionEventKind.REFUND)).category)
        assertNull(classifier.classify(candidate(TransactionEventKind.TRANSFER)).category)
        assertEquals("红包", classifier.classify(candidate(TransactionEventKind.RED_PACKET)).category)
    }

    private fun telecomCandidate(merchant: String = "中国电信") = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.ACCESSIBILITY,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = 100,
        direction = Direction.EXPENSE,
        merchant = merchant,
        account = "零钱",
        occurredAt = NOW,
        confidence = 95,
        evidenceDigest = "a".repeat(64),
    )

    private fun candidate(eventKind: TransactionEventKind) = telecomCandidate(merchant = "便利店").copy(
        eventKind = eventKind,
        evidenceDigest = ('a'.code + eventKind.ordinal).toChar().toString().repeat(64),
    )

    private class ClassifierStore : RecognitionStore {
        var rule: MerchantRuleMatch? = null
        var lastRuleLookup: String? = null
        val visibleCategories = mutableSetOf<String>()

        override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? {
            lastRuleLookup = normalizedMerchant
            return rule?.takeIf { it.merchantPattern == normalizedMerchant }
        }

        override suspend fun isCategoryVisible(name: String): Boolean = name in visibleCategories

        override suspend fun findReceipt(digest: String): RecognitionReceipt? = unsupported()
        override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> = unsupported()
        override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long = unsupported()
        override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) = unsupported<Unit>()
        override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) = unsupported<Unit>()
        override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? = unsupported()
        override suspend fun rememberMerchantRule(merchant: String, category: String, account: String?) =
            unsupported<Unit>()

        override suspend fun confirmAndRemember(transaction: Transaction): Long = unsupported()

        private fun <T> unsupported(): T = error("Unexpected store operation")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-29T10:00:00Z")
    }
}
