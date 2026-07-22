package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentParserTest {
    private val instant = Instant.parse("2026-01-02T03:04:05Z")

    @Test
    fun `wechat expense parses yuan into minor units`() {
        val candidate = WechatParser.parse(
            NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant),
        )

        assertEquals(2_800L, candidate?.amountMinor)
        assertEquals(Direction.EXPENSE, candidate?.direction)
    }

    @Test
    fun `alipay income parses an exact two decimal amount`() {
        val candidate = AlipayParser.parse(
            NotificationText("支付宝", "收款到账 人民币6.05", "com.eg.android.AlipayGphone", instant),
        )

        assertEquals(605L, candidate?.amountMinor)
        assertEquals(Direction.INCOME, candidate?.direction)
    }

    @Test
    fun `unknown notification returns null`() {
        assertNull(
            AlipayParser.parse(
                NotificationText("服务通知", "你有一条新消息", "com.eg.android.AlipayGphone", instant),
            ),
        )
    }

    @Test
    fun `source package allowlists are enforced`() {
        val forged = NotificationText("微信支付", "支付成功 ￥28.00", "example.untrusted", instant)

        assertNull(WechatParser.parse(forged))
        assertNull(AlipayParser.parse(forged))
    }

    @Test
    fun `source specific keywords do not cross match`() {
        assertNull(
            WechatParser.parse(
                NotificationText("账户变动", "银行卡消费 人民币68.50", "com.tencent.mm", instant),
            ),
        )
    }

    @Test
    fun `allowlisted bank debit notification parses as expense`() {
        val parser = BankNotificationParser(setOf("com.example.bank"))
        val candidate = parser.parse(
            NotificationText("账户变动", "消费人民币68.50", "com.example.bank", instant),
        )

        assertEquals(6_850L, candidate?.amountMinor)
        assertEquals(Direction.EXPENSE, candidate?.direction)
    }

    @Test
    fun `allowlisted bank credit notification parses as income`() {
        val parser = BankNotificationParser(setOf("com.example.bank"))
        val candidate = parser.parse(
            NotificationText("交易提醒", "收入 CNY 100.00", "com.example.bank", instant),
        )

        assertEquals(10_000L, candidate?.amountMinor)
        assertEquals(Direction.INCOME, candidate?.direction)
    }

    @Test
    fun `disabled bank package returns null`() {
        val parser = BankNotificationParser(setOf("com.enabled.bank"))

        assertNull(
            parser.parse(NotificationText("账户变动", "消费人民币68.50", "com.disabled.bank", instant)),
        )
    }

    @Test
    fun `ambiguous supported bank message becomes uncertain candidate`() {
        val candidate = BankNotificationParser(setOf("com.example.bank")).parse(
            NotificationText("账户变动", "交易金额 人民币88.00", "com.example.bank", instant),
        )

        assertNotNull(candidate)
        assertNull(candidate?.direction)
        assertTrue(candidate!!.confidence < CandidateProcessor.DIRECT_SAVE_CONFIDENCE)
    }

    @Test
    fun `unrelated bank notification returns null even with amount`() {
        assertNull(
            BankNotificationParser(setOf("com.example.bank")).parse(
                NotificationText("活动通知", "优惠券价值 人民币88.00", "com.example.bank", instant),
            ),
        )
    }

    @Test
    fun `malformed zero overflowing and overprecision amounts are rejected`() {
        val bodies = listOf(
            "支付成功 ￥0",
            "支付成功 ￥0.00",
            "支付成功 ￥12.345",
            "支付成功 ￥12.",
            "支付成功 ￥999999999999999999999999999999.00",
        )

        bodies.forEach { body ->
            assertNull(WechatParser.parse(NotificationText("微信支付", body, "com.tencent.mm", instant)), body)
        }
    }

    @Test
    fun `whole and one decimal amounts convert exactly`() {
        val whole = WechatParser.parse(
            NotificationText("微信支付", "支付成功 ￥7", "com.tencent.mm", instant),
        )
        val tenth = WechatParser.parse(
            NotificationText("微信支付", "支付成功 ￥7.1", "com.tencent.mm", instant),
        )

        assertEquals(700L, whole?.amountMinor)
        assertEquals(710L, tenth?.amountMinor)
    }

    @Test
    fun `candidate exposes only hashes rather than notification text`() {
        val rawBody = "支付成功 ￥28.00 测试商户"
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", rawBody, "com.tencent.mm", instant)),
        )

        assertTrue(candidate.redactedDigest.matches(Regex("[0-9a-f]{64}")))
        assertTrue(candidate.dedupeKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse(candidate.toString().contains(rawBody))
        assertFalse(candidate.redactedDigest.contains("测试商户"))
        assertFalse(candidate.dedupeKey.contains("测试商户"))
    }

    @Test
    fun `dedupe key uses normalized redacted title and two minute bucket`() {
        val first = requireNotNull(
            WechatParser.parse(
                NotificationText(" 微信  支付 1234 ", "支付成功 ￥28.00", "com.tencent.mm", instant),
            ),
        )
        val normalizedSameBucket = requireNotNull(
            WechatParser.parse(
                NotificationText(
                    "微信 支付 9876",
                    "支付成功 ￥28.00",
                    "com.tencent.mm",
                    instant.plusSeconds(30),
                ),
            ),
        )
        val laterBucket = requireNotNull(
            WechatParser.parse(
                NotificationText(
                    "微信 支付 9876",
                    "支付成功 ￥28.00",
                    "com.tencent.mm",
                    instant.plusSeconds(180),
                ),
            ),
        )

        assertEquals(first.dedupeKey, normalizedSameBucket.dedupeKey)
        assertNotEquals(first.dedupeKey, laterBucket.dedupeKey)
    }

    @Test
    fun `candidate processor checks dedupe before saving`() = runBlocking {
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val existing = notificationTransaction(candidate, pending = false).copy(id = 41L)
        val repository = RecordingRepository(existingByKey = existing)

        val result = CandidateProcessor(repository).process(candidate)

        assertNull(result)
        assertEquals(listOf(candidate.dedupeKey), repository.lookups)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `candidate processor maps uncertain candidate to pending confirmation`() = runBlocking {
        val candidate = requireNotNull(
            BankNotificationParser(setOf("com.example.bank")).parse(
                NotificationText("账户变动", "交易金额 人民币88.00", "com.example.bank", instant),
            ),
        )
        val repository = RecordingRepository()

        assertEquals(73L, CandidateProcessor(repository).process(candidate))

        val saved = repository.saved.single()
        assertTrue(saved.pendingConfirmation)
        assertEquals(Source.NOTIFICATION, saved.source)
        assertEquals(candidate.dedupeKey, saved.dedupeKey)
        assertEquals(8_800L, saved.amountMinor)
    }

    @Test
    fun `candidate processor propagates repository failures`() {
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val repository = RecordingRepository(failure = IllegalStateException("repository unavailable"))

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { CandidateProcessor(repository).process(candidate) }
        }

        assertEquals("repository unavailable", failure.message)
    }

    private fun notificationTransaction(candidate: PaymentCandidate, pending: Boolean) = Transaction(
        amountMinor = candidate.amountMinor,
        direction = candidate.direction ?: Direction.EXPENSE,
        category = "待确认",
        occurredAt = candidate.occurredAt,
        source = Source.NOTIFICATION,
        pendingConfirmation = pending,
        dedupeKey = candidate.dedupeKey,
    )

    private class RecordingRepository(
        private val existingByKey: Transaction? = null,
        private val failure: RuntimeException? = null,
    ) : LedgerRepository {
        val lookups = mutableListOf<String>()
        val saved = mutableListOf<Transaction>()

        override fun observeTransactions(): Flow<List<Transaction>> = emptyFlow()

        override suspend fun save(transaction: Transaction): Long {
            failure?.let { throw it }
            saved += transaction
            return 73L
        }

        override suspend fun delete(id: Long) = Unit

        override suspend fun findByDedupeKey(key: String): Transaction? {
            failure?.let { throw it }
            lookups += key
            return existingByKey
        }

        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) = Unit
    }
}
