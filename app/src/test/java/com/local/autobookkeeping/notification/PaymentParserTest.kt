package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.DuplicateDedupeKeyException
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
    fun `signed and malformed grouped amounts are rejected`() {
        val bodies = listOf(
            "支付成功 -￥12.34",
            "支付成功 +￥12.34",
            "支付成功 ￥-12.34",
            "支付成功 ￥+12.34",
            "支付成功 ￥1,23.45",
            "支付成功 ￥12,34,567.89",
        )

        bodies.forEach { body ->
            assertNull(WechatParser.parse(NotificationText("微信支付", body, "com.tencent.mm", instant)), body)
        }
    }

    @Test
    fun `well formed grouped amount parses exactly`() {
        val candidate = WechatParser.parse(
            NotificationText("微信支付", "支付成功 人民币1,234,567.89", "com.tencent.mm", instant),
        )

        assertEquals(123_456_789L, candidate?.amountMinor)
    }

    @Test
    fun `transaction context selects expense instead of balance amount`() {
        val candidate = WechatParser.parse(
            NotificationText(
                "微信支付",
                "余额人民币1000.00，支付成功人民币28.00",
                "com.tencent.mm",
                instant,
            ),
        )

        assertEquals(2_800L, candidate?.amountMinor)
    }

    @Test
    fun `transaction context supports amount before keyword`() {
        val candidate = WechatParser.parse(
            NotificationText("微信支付", "人民币28.00支付成功", "com.tencent.mm", instant),
        )

        assertEquals(2_800L, candidate?.amountMinor)
    }

    @Test
    fun `conflicting direction signals stay uncertain for every supported source`() {
        val wechat = WechatParser.parse(
            NotificationText("微信支付", "支付成功后退款到账 ￥12.00", "com.tencent.mm", instant),
        )
        val alipay = AlipayParser.parse(
            NotificationText(
                "支付宝",
                "付款成功后退款到账 人民币12.00",
                "com.eg.android.AlipayGphone",
                instant,
            ),
        )
        val bank = BankNotificationParser(setOf("com.example.bank")).parse(
            NotificationText("账户交易", "消费后退款入账 人民币12.00", "com.example.bank", instant),
        )

        listOf(wechat, alipay, bank).forEach { candidate ->
            assertNotNull(candidate)
            assertNull(candidate?.direction)
            assertTrue(candidate!!.confidence < CandidateProcessor.DIRECT_SAVE_CONFIDENCE)
        }
    }

    @Test
    fun `promotional transaction language is rejected`() {
        assertNull(
            WechatParser.parse(
                NotificationText("优惠活动", "支付成功最高可享优惠 ￥28.00", "com.tencent.mm", instant),
            ),
        )
        assertNull(
            BankNotificationParser(setOf("com.example.bank")).parse(
                NotificationText("账户活动", "消费满减可享 人民币28.00", "com.example.bank", instant),
            ),
        )
    }

    @Test
    fun `bank english keywords are normalized with root locale`() {
        val parser = BankNotificationParser(setOf("com.example.bank"))
        val debit = parser.parse(
            NotificationText("Account transaction", "Debit CNY 12.00", "com.example.bank", instant),
        )
        val credit = parser.parse(
            NotificationText("Account transaction", "CrEdIt RMB 9.00", "com.example.bank", instant),
        )

        assertEquals(Direction.EXPENSE, debit?.direction)
        assertEquals(Direction.INCOME, credit?.direction)
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
    fun `notification text string representation is redacted`() {
        val rawTitle = "匿名标题"
        val rawBody = "支付成功 ￥28.00 匿名商户"

        val rendered = NotificationText(rawTitle, rawBody, "com.tencent.mm", instant).toString()

        assertFalse(rendered.contains(rawTitle))
        assertFalse(rendered.contains(rawBody))
        assertTrue(rendered.contains("com.tencent.mm"))
    }

    @Test
    fun `hashes use only explicit non sensitive signals and match known vectors`() {
        val first = requireNotNull(
            WechatParser.parse(
                NotificationText("匿名标题甲", "支付成功 ￥28.00 匿名商户甲", "com.tencent.mm", instant),
            ),
        )
        val second = requireNotNull(
            WechatParser.parse(
                NotificationText("匿名标题乙", "匿名备注 支付成功 ￥28.00", "com.tencent.mm", instant),
            ),
        )

        assertEquals("13152155a760c0b7d97244e26ff1292f484bd92941d1322ef0a7a8a8d7812a43", first.redactedDigest)
        assertEquals("3a0603d2c652d33ea9caeec72abaa5eca66c9b1440a1c38a264388e7db37f761", first.dedupeKey)
        assertEquals(first.redactedDigest, second.redactedDigest)
        assertEquals(first.dedupeKey, second.dedupeKey)
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
    fun `opposite directions never share a dedupe key`() {
        val expense = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val income = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "收款到账 ￥28.00", "com.tencent.mm", instant)),
        )

        assertNotEquals(expense.dedupeKey, income.dedupeKey)
    }

    @Test
    fun `fixed two minute buckets differ across an adjacent boundary`() {
        val boundary = Instant.ofEpochSecond((instant.epochSecond / 120L + 1L) * 120L)
        val before = requireNotNull(
            WechatParser.parse(
                NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", boundary.minusSeconds(1)),
            ),
        )
        val after = requireNotNull(
            WechatParser.parse(
                NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", boundary),
            ),
        )

        assertNotEquals(before.dedupeKey, after.dedupeKey)
    }

    @Test
    fun `clear source specific pattern reaches direct save path`() = runBlocking {
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val repository = RecordingRepository()

        CandidateProcessor(repository).process(candidate)

        assertTrue(candidate.confidence >= CandidateProcessor.DIRECT_SAVE_CONFIDENCE)
        assertFalse(repository.saved.single().pendingConfirmation)
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
        val repository = RecordingRepository(lookupFailure = IllegalStateException("repository unavailable"))

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { CandidateProcessor(repository).process(candidate) }
        }

        assertEquals("repository unavailable", failure.message)
    }

    @Test
    fun `candidate processor treats concurrent unique conflict as duplicate`() = runBlocking {
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val repository = RecordingRepository(
            saveFailure = DuplicateDedupeKeyException(candidate.dedupeKey),
        )

        val result = CandidateProcessor(repository).process(candidate)

        assertNull(result)
        assertEquals(listOf(candidate.dedupeKey), repository.lookups)
    }

    @Test
    fun `candidate processor propagates unrelated save failure`() {
        val candidate = requireNotNull(
            WechatParser.parse(NotificationText("微信支付", "支付成功 ￥28.00", "com.tencent.mm", instant)),
        )
        val repository = RecordingRepository(saveFailure = IllegalStateException("save unavailable"))

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { CandidateProcessor(repository).process(candidate) }
        }

        assertEquals("save unavailable", failure.message)
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
        private val lookupFailure: RuntimeException? = null,
        private val saveFailure: RuntimeException? = null,
    ) : LedgerRepository {
        val lookups = mutableListOf<String>()
        val saved = mutableListOf<Transaction>()

        override fun observeTransactions(): Flow<List<Transaction>> = emptyFlow()

        override suspend fun save(transaction: Transaction): Long {
            saveFailure?.let { throw it }
            saved += transaction
            return 73L
        }

        override suspend fun delete(id: Long) = Unit

        override suspend fun findByDedupeKey(key: String): Transaction? {
            lookupFailure?.let { throw it }
            lookups += key
            return existingByKey
        }

        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) = Unit
    }
}
