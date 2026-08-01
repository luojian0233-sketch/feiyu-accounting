package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentPageParserTest {
    @Test
    fun `wechat telecom payment extracts expense merchant account and amount`() {
        val outcome = WechatPageParser.parse(
            page(
                "微信支付",
                "中国电信",
                "使用零钱支付",
                "¥1.00",
                "账单详情",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single

        assertEquals(100L, outcome.candidate.amountMinor)
        assertEquals(Direction.EXPENSE, outcome.candidate.direction)
        assertEquals("中国电信", outcome.candidate.merchant)
        assertEquals("零钱", outcome.candidate.account)
        assertEquals(TransactionEventKind.PAYMENT, outcome.candidate.eventKind)
        assertEquals(PaymentPlatform.WECHAT, outcome.candidate.platform)
        assertEquals(CaptureChannel.ACCESSIBILITY, outcome.candidate.channel)
    }

    @Test
    fun `alipay receipt and refund are income`() {
        assertOutcome(
            eventKind = TransactionEventKind.RECEIPT,
            direction = Direction.INCOME,
            outcome = AlipayPageParser.parse(
                page("收款到账", "便利店", "¥8.80", packageName = ALIPAY_PACKAGE),
            ),
        )
        assertOutcome(
            eventKind = TransactionEventKind.REFUND,
            direction = Direction.INCOME,
            outcome = AlipayPageParser.parse(
                page("退款成功", "中国电信", "¥1.00", packageName = ALIPAY_PACKAGE),
            ),
        )
    }

    @Test
    fun `both platform parsers map every explicit transaction signal to the required event and direction`() {
        val expectations = listOf(
            Triple("支付成功", TransactionEventKind.PAYMENT, Direction.EXPENSE),
            Triple("已支付", TransactionEventKind.PAYMENT, Direction.EXPENSE),
            Triple("收款到账", TransactionEventKind.RECEIPT, Direction.INCOME),
            Triple("退款到账", TransactionEventKind.REFUND, Direction.INCOME),
            Triple("退款成功", TransactionEventKind.REFUND, Direction.INCOME),
            Triple("转入", TransactionEventKind.TRANSFER, Direction.INCOME),
            Triple("转出", TransactionEventKind.TRANSFER, Direction.EXPENSE),
            Triple("收到红包", TransactionEventKind.RED_PACKET, Direction.INCOME),
            Triple("发出红包", TransactionEventKind.RED_PACKET, Direction.EXPENSE),
        )

        val platforms = listOf(
            Triple("wechat", WECHAT_PACKAGE, WechatPageParser as PaymentPageParser),
            Triple("alipay", ALIPAY_PACKAGE, AlipayPageParser as PaymentPageParser),
        )

        platforms.forEach { (platform, packageName, parser) ->
            expectations.forEach { (signal, eventKind, direction) ->
                assertOutcome(
                    eventKind = eventKind,
                    direction = direction,
                    outcome = parser.parse(
                        page(
                            signal,
                            "商户：便利店",
                            "实付 ¥12.34",
                            packageName = packageName,
                        ),
                    ),
                    message = "$platform $signal",
                )
            }
        }
    }

    @Test
    fun `platform parsers require exact package names`() {
        val forgedWechat = page(
            "微信支付",
            "支付成功",
            "¥1.00",
            packageName = "com.tencent.mm.fake",
        )
        val forgedAlipay = page(
            "支付宝",
            "收款到账",
            "¥1.00",
            packageName = "com.eg.android.alipaygphone",
        )

        assertRejected(WechatPageParser.parse(forgedWechat), RecognitionRejectReason.NOT_PAYMENT_PAGE)
        assertRejected(AlipayPageParser.parse(forgedAlipay), RecognitionRejectReason.NOT_PAYMENT_PAGE)
    }

    @Test
    fun `amount is selected only from its card and transaction clause`() {
        val localClause = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "余额 ¥1,000.00，实付 ¥28.00",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single
        assertEquals(2_800L, localClause.candidate.amountMinor)

        val crossCard = WechatPageParser.parse(
            cardsPage(
                WECHAT_PACKAGE,
                listOf("微信支付", "支付成功", "商户：便利店"),
                listOf("总资产", "余额 ¥1,000.00"),
            ),
        )
        assertRejected(crossCard)
    }

    @Test
    fun `malformed zero signed overflowing and overprecision amounts are rejected`() {
        val amounts = listOf(
            "¥0.00",
            "-¥12.34",
            "¥-12.34",
            "¥12.345",
            "¥999999999999999999999999999999.00",
        )

        amounts.forEach { amount ->
            assertRejected(
                WechatPageParser.parse(
                    page("微信支付", "支付成功", amount, packageName = WECHAT_PACKAGE),
                ),
                message = amount,
            )
        }
    }

    @Test
    fun `invalid primary amount clause prevents fallback to a valid fee`() {
        val invalidPrimaryAmounts = listOf(
            "实付 ¥12.345",
            "实付 ¥-12.34",
            "实付 ¥1,23.45",
            "实付 ¥999999999999999999999999999999.00",
        )

        invalidPrimaryAmounts.forEach { primaryAmount ->
            assertRejected(
                WechatPageParser.parse(
                    page(
                        "微信支付",
                        "支付成功",
                        primaryAmount,
                        "手续费 ¥1.00",
                        packageName = WECHAT_PACKAGE,
                    ),
                ),
                RecognitionRejectReason.INCOMPLETE_FIELDS,
                primaryAmount,
            )
        }
    }

    @Test
    fun `multiple equally ranked bill cards require selection in top to bottom order`() {
        val outcome = GenericBillParser.parse(
            cardsPage(
                null,
                listOf("账单详情", "支付成功", "商户：甲店", "实付 ¥1.00"),
                listOf("账单详情", "支付成功", "商户：乙店", "实付 ¥2.00"),
            ),
        ) as ExtractionOutcome.Multiple

        assertEquals(listOf(100L, 200L), outcome.options.map { it.amountMinor })
        assertEquals(listOf("甲店", "乙店"), outcome.options.map { it.merchant })
    }

    @Test
    fun `repeated signatures split vertically adjacent cards despite a sixty pixel gap`() {
        val first = positionedCard(
            lines = listOf("账单详情", "支付成功", "商户：甲店", "付款方式：花呗", "实付 ¥1.00"),
            left = 40,
            top = 80,
        )
        val second = positionedCard(
            lines = listOf("账单详情", "支付成功", "实付 ¥2.00"),
            left = 40,
            top = first.maxOf { it.bounds.bottom } + 60,
        )
        val outcome = GenericBillParser.parse(
            PageSnapshot(null, first + second, OBSERVED_AT),
        ) as ExtractionOutcome.Multiple

        assertEquals(listOf(100L, 200L), outcome.options.map { it.amountMinor })
        assertEquals(listOf("甲店", null), outcome.options.map { it.merchant })
    }

    @Test
    fun `two dimensional bounds keep side by side bill cards separate`() {
        val leftCard = positionedCard(
            lines = listOf("账单详情", "支付成功", "商户：甲店", "实付 ¥3.00"),
            left = 40,
            top = 80,
            width = 360,
        )
        val rightCard = positionedCard(
            lines = listOf("账单详情", "支付成功", "商户：乙店", "实付 ¥4.00"),
            left = 520,
            top = 80,
            width = 360,
        )
        val outcome = GenericBillParser.parse(
            PageSnapshot(null, leftCard + rightCard, OBSERVED_AT),
        ) as ExtractionOutcome.Multiple

        assertEquals(listOf(300L, 400L), outcome.options.map { it.amountMinor })
        assertEquals(listOf("甲店", "乙店"), outcome.options.map { it.merchant })
    }

    @Test
    fun `visible transaction time is preferred over capture time`() {
        val outcome = WechatPageParser.parse(
            page(
                "微信支付",
                "中国电信",
                "¥1.00",
                "2026-07-29 18:19",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single

        assertEquals(Instant.parse("2026-07-29T10:19:00Z"), outcome.candidate.occurredAt)
    }

    @Test
    fun `month day and card local clock times use the bookkeeping zone`() {
        val monthDay = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "¥1.00",
                "07-28 09:15",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single
        val localClock = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "¥1.00",
                "交易时间：09:16",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single

        assertEquals(Instant.parse("2026-07-28T01:15:00Z"), monthDay.candidate.occurredAt)
        assertEquals(Instant.parse("2026-07-29T01:16:00Z"), localClock.candidate.occurredAt)
    }

    @Test
    fun `system status bar clock is not used as transaction time`() {
        val statusClock = ObservedTextElement(
            text = "09:16",
            bounds = TextBounds(left = 20, top = 4, right = 120, bottom = 28),
        )
        val paymentCard = positionedCard(
            lines = listOf("微信支付", "支付成功", "商户：便利店", "实付 ¥1.00"),
            left = 40,
            top = 45,
            width = 560,
        )
        val outcome = WechatPageParser.parse(
            PageSnapshot(WECHAT_PACKAGE, listOf(statusClock) + paymentCard, OBSERVED_AT),
        ) as ExtractionOutcome.Single

        assertEquals(OBSERVED_AT, outcome.candidate.occurredAt)
    }

    @Test
    fun `selected card does not borrow a visible time from another geometry group`() {
        val observedAt = Instant.parse("2026-07-29T11:05:00Z")
        val outcome = WechatPageParser.parse(
            cardsPage(
                WECHAT_PACKAGE,
                listOf("微信支付", "支付成功", "商户：便利店", "实付 ¥28.00"),
                listOf("帮助与服务", "2026-07-20 08:00"),
                observedAt = observedAt,
            ),
        ) as ExtractionOutcome.Single

        assertEquals(observedAt, outcome.candidate.occurredAt)
    }

    @Test
    fun `only exact account labels and masked bank cards are normalized`() {
        val exact = AlipayPageParser.parse(
            page(
                "支付成功",
                "商户：便利店",
                "付款方式：花呗",
                "¥12.00",
                packageName = ALIPAY_PACKAGE,
            ),
        ) as ExtractionOutcome.Single
        val masked = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "商户：便利店",
                "使用 招商银行储蓄卡（尾号 1234） 支付",
                "¥12.00",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single
        val unrelated = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "商户：便利店",
                "使用会员积分抵扣",
                "¥12.00",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single

        assertEquals("花呗", exact.candidate.account)
        assertEquals("招商银行储蓄卡(1234)", masked.candidate.account)
        assertNull(unrelated.candidate.account)
    }

    @Test
    fun `isolated allowlisted account word has no account semantics`() {
        val outcome = WechatPageParser.parse(
            page(
                "微信支付",
                "支付成功",
                "商户：便利店",
                "花呗",
                "实付 ¥12.00",
                packageName = WECHAT_PACKAGE,
            ),
        ) as ExtractionOutcome.Single

        assertNull(outcome.candidate.account)
    }

    @Test
    fun `account value aligned with an explicit payment method label is accepted`() {
        val elements = positionedCard(
            lines = listOf("微信支付", "支付成功", "商户：便利店"),
            left = 40,
            top = 80,
            width = 560,
        ) + listOf(
            ObservedTextElement("付款方式", TextBounds(40, 180, 180, 208)),
            ObservedTextElement("花呗", TextBounds(220, 180, 320, 208)),
            ObservedTextElement("实付 ¥12.00", TextBounds(40, 220, 600, 248)),
        )
        val outcome = WechatPageParser.parse(
            PageSnapshot(WECHAT_PACKAGE, elements, OBSERVED_AT),
        ) as ExtractionOutcome.Single

        assertEquals("花呗", outcome.candidate.account)
    }

    @Test
    fun `chat balance asset coupon and error pages are rejected`() {
        assertRejected(
            WechatPageParser.parse(
                page("聊天", "已支付¥1.00", packageName = WECHAT_PACKAGE),
            ),
        )
        assertRejected(
            AlipayPageParser.parse(
                page("总资产", "余额 ¥10000.00", packageName = ALIPAY_PACKAGE),
            ),
        )
        assertRejected(GenericBillParser.parse(page("优惠券", "最高减 ¥20.00", packageName = null)))
        assertRejected(
            WechatPageParser.parse(
                page("微信支付", "页面加载失败", "¥1.00", packageName = WECHAT_PACKAGE),
            ),
        )
    }

    @Test
    fun `ambiguous amount and conflicting event cards are rejected`() {
        assertRejected(
            WechatPageParser.parse(
                page(
                    "微信支付",
                    "支付成功",
                    "实付 ¥1.00，实付 ¥2.00",
                    packageName = WECHAT_PACKAGE,
                ),
            ),
            RecognitionRejectReason.INCOMPLETE_FIELDS,
        )
        assertRejected(
            WechatPageParser.parse(
                page(
                    "微信支付",
                    "支付成功后退款到账",
                    "实付 ¥1.00",
                    packageName = WECHAT_PACKAGE,
                ),
            ),
            RecognitionRejectReason.CONFLICTING_DIRECTION,
        )
    }

    @Test
    fun `generic parsing remains an explicit screenshot fallback`() {
        val snapshot = page(
            "账单详情",
            "支付成功",
            "商户：便利店",
            "实付 ¥6.66",
            packageName = WECHAT_PACKAGE,
        )
        val generic = GenericBillParser.parse(snapshot) as ExtractionOutcome.Single

        assertEquals(PaymentPlatform.OTHER, generic.candidate.platform)
        assertEquals(CaptureChannel.SCREENSHOT, generic.candidate.channel)
        assertNotEquals(PaymentPlatform.WECHAT, generic.candidate.platform)
    }

    @Test
    fun `digest and string representations never retain unrelated raw page text`() {
        val firstSecret = "仅供测试的原始页面秘密甲"
        val secondSecret = "完全不同的原始页面秘密乙"
        val firstPage = page(
            "微信支付",
            "支付成功",
            "商户：便利店",
            "实付 ¥12.00",
            firstSecret,
            packageName = WECHAT_PACKAGE,
        )
        val secondPage = page(
            "微信支付",
            "支付成功",
            "商户：便利店",
            "实付 ¥12.00",
            secondSecret,
            packageName = WECHAT_PACKAGE,
        )
        val first = WechatPageParser.parse(firstPage) as ExtractionOutcome.Single
        val second = WechatPageParser.parse(secondPage) as ExtractionOutcome.Single

        assertEquals(first.candidate.evidenceDigest, second.candidate.evidenceDigest)
        assertTrue(first.candidate.evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.candidate.evidenceDigest.contains(firstSecret))
        assertFalse(firstPage.toString().contains(firstSecret))
        assertFalse(firstPage.elements.first().toString().contains("微信支付"))
        assertFalse(first.toString().contains(firstSecret))
    }

    private fun assertOutcome(
        eventKind: TransactionEventKind,
        direction: Direction,
        outcome: ExtractionOutcome,
        message: String? = null,
    ) {
        val candidate = (outcome as ExtractionOutcome.Single).candidate
        assertEquals(eventKind, candidate.eventKind, message)
        assertEquals(direction, candidate.direction, message)
    }

    private fun assertRejected(
        outcome: ExtractionOutcome,
        reason: RecognitionRejectReason? = null,
        message: String? = null,
    ) {
        assertTrue(outcome is ExtractionOutcome.Rejected, message ?: outcome.toString())
        if (reason != null) {
            assertEquals(reason, (outcome as ExtractionOutcome.Rejected).reason, message)
        }
    }

    private fun page(
        vararg lines: String,
        packageName: String?,
        observedAt: Instant = OBSERVED_AT,
    ): PageSnapshot = PageSnapshot(
        packageName = packageName,
        elements = card(lines.toList(), top = 80),
        observedAt = observedAt,
    )

    private fun cardsPage(
        packageName: String?,
        vararg cards: List<String>,
        observedAt: Instant = OBSERVED_AT,
    ): PageSnapshot = PageSnapshot(
        packageName = packageName,
        elements = cards.flatMapIndexed { index, lines -> card(lines, top = 80 + index * 400) },
        observedAt = observedAt,
    )

    private fun card(lines: List<String>, top: Int): List<ObservedTextElement> =
        lines.mapIndexed { index, text ->
            val lineTop = top + index * 40
            ObservedTextElement(
                text = text,
                bounds = TextBounds(left = 40, top = lineTop, right = 600, bottom = lineTop + 28),
            )
        }

    private fun positionedCard(
        lines: List<String>,
        left: Int,
        top: Int,
        width: Int = 560,
    ): List<ObservedTextElement> = lines.mapIndexed { index, text ->
        val lineTop = top + index * 30
        ObservedTextElement(
            text = text,
            bounds = TextBounds(left, lineTop, left + width, lineTop + 20),
        )
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        val OBSERVED_AT: Instant = Instant.parse("2026-07-29T11:05:00Z")
    }
}
