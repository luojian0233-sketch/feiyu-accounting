package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustedAccessibilityAnchorMergerTest {
    @Test
    fun `Alipay merge copies only exact detail anchors`() {
        val detail = element("账单详情", top = 0)
        val ocrAmount = element("¥1.00", top = 80)
        val original = snapshot(
            ALIPAY_PACKAGE,
            detail,
            element("支付宝", 20),
            element("支付成功", 40),
            element("¥999.00", 60),
            element("商户：不可信字段", 100),
        )

        val merged = TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAmount))

        assertEquals(listOf(ocrAmount, detail), merged)
    }

    @Test
    fun `wrong package never contributes an anchor`() {
        val amount = element("¥1.00", 80)
        val original = snapshot("com.tencent.mm", element("账单详情", 0))

        assertEquals(
            listOf(amount),
            TrustedAccessibilityAnchorMerger.merge(original, listOf(amount)),
        )
    }

    @Test
    fun `standalone Alipay title and containing text are not trusted anchors`() {
        val amount = element("¥1.00", 80)
        val original = snapshot(
            ALIPAY_PACKAGE,
            element("支付宝", 0),
            element("支付宝账单详情服务", 40),
        )

        assertEquals(
            listOf(amount),
            TrustedAccessibilityAnchorMerger.merge(original, listOf(amount)),
        )
    }

    @Test
    fun `trimmed text and identical bounds deduplicate an OCR anchor`() {
        val ocrAnchor = element("账单详情", 0)
        val original = snapshot(ALIPAY_PACKAGE, element(" 账单详情 ", 0))

        assertEquals(
            listOf(ocrAnchor),
            TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAnchor)),
        )
    }

    @Test
    fun `same trusted text at different bounds remains distinct`() {
        val ocrAnchor = element("账单详情", 40)
        val trustedAnchor = element("账单详情", 0)
        val original = snapshot(ALIPAY_PACKAGE, trustedAnchor)

        assertEquals(
            listOf(ocrAnchor, trustedAnchor),
            TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAnchor)),
        )
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
        val NOW: Instant = Instant.parse("2026-08-14T07:00:00Z")
    }
}
