package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrustedAlipayBareAmountRecoveryTest {
    @Test
    fun `unique bare two-decimal amount is recovered in exact Alipay detail context`() {
        val bare = element("1.00", 80)

        val recovered = TrustedAlipayBareAmountRecovery.recover(
            snapshot(ALIPAY_PACKAGE, element("账单详情", 0)),
            listOf(element("中国电信", 40), bare),
        )

        assertEquals("¥1.00", recovered.last().text)
        assertEquals(bare.bounds, recovered.last().bounds)
        assertEquals("1.00", bare.text)
    }

    @Test
    fun `bill detail alias supports comma decimal normalization`() {
        val recovered = TrustedAlipayBareAmountRecovery.recover(
            snapshot(ALIPAY_PACKAGE, element("账单明细", 0)),
            listOf(element("12,34", 80)),
        )

        assertEquals("¥12.34", recovered.single().text)
    }

    @Test
    fun `wrong package missing anchor and containing anchor text stay unchanged`() {
        val bare = listOf(element("1.00", 80))
        val originals = listOf(
            snapshot("com.tencent.mm", element("账单详情", 0)),
            snapshot(ALIPAY_PACKAGE, element("支付宝", 0)),
            snapshot(ALIPAY_PACKAGE, element("支付宝账单详情服务", 0)),
        )

        originals.forEach { original ->
            assertSame(bare, TrustedAlipayBareAmountRecovery.recover(original, bare))
        }
    }

    @Test
    fun `existing currency amount or non-unique bare decimals stay unchanged`() {
        val cases = listOf(
            listOf(element("¥1.00", 40), element("2.00", 80)),
            listOf(element("￥1.00", 40), element("2.00", 80)),
            listOf(element("人民币 1.00", 40), element("2.00", 80)),
            listOf(element("CNY 1.00", 40), element("2.00", 80)),
            listOf(element("rmb 1.00", 40), element("2.00", 80)),
            listOf(element("1.00", 40), element("2.00", 80)),
            emptyList(),
        )
        val original = snapshot(ALIPAY_PACKAGE, element("账单详情", 0))

        cases.forEach { input ->
            assertSame(input, TrustedAlipayBareAmountRecovery.recover(original, input))
        }
    }

    @Test
    fun `unsafe bare number forms stay unchanged`() {
        val original = snapshot(ALIPAY_PACKAGE, element("账单详情", 0))
        val unsafe = listOf(
            "1",
            "1.0",
            "1.000",
            "+1.00",
            "-1.00",
            "1.00 元",
            "实付1.00",
            "123456789.00",
        )

        unsafe.forEach { text ->
            val input = listOf(element(text, 80))
            assertSame(input, TrustedAlipayBareAmountRecovery.recover(original, input))
        }
    }

    private fun snapshot(
        packageName: String,
        vararg elements: ObservedTextElement,
    ) = PageSnapshot(
        packageName = packageName,
        elements = elements.toList(),
        observedAt = NOW,
    )

    private fun element(text: String, top: Int) = ObservedTextElement(
        text = text,
        bounds = TextBounds(0, top, 200, top + 30),
    )

    private companion object {
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        val NOW: Instant = Instant.parse("2026-08-15T03:00:00Z")
    }
}
