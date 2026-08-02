package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecognitionDebouncerTest {
    private val debouncer = RecognitionDebouncer(windowMillis = 1_000)

    @Test
    fun `same normalized page inside debounce window is processed once`() {
        assertTrue(debouncer.shouldProcess(page("微信  支付", "¥ 1.00"), 5_000))
        assertFalse(debouncer.shouldProcess(page(" 微信 支付 ", "¥ 1.00"), 5_500))
    }

    @Test
    fun `same page is processed again after debounce window`() {
        assertTrue(debouncer.shouldProcess(page("微信支付"), 5_000))
        assertTrue(debouncer.shouldProcess(page("微信支付"), 6_000))
    }

    @Test
    fun `geometry remains part of normalized page identity`() {
        assertTrue(debouncer.shouldProcess(page("微信支付", top = 0), 5_000))
        assertTrue(debouncer.shouldProcess(page("微信支付", top = 60), 5_100))
    }

    private fun page(text: String, second: String? = null, top: Int = 0) = PageSnapshot(
        packageName = "com.tencent.mm",
        elements = listOfNotNull(
            ObservedTextElement(text, TextBounds(0, top, 200, top + 30)),
            second?.let { ObservedTextElement(it, TextBounds(0, top + 40, 200, top + 70)) },
        ),
        observedAt = Instant.parse("2026-07-29T10:00:00Z"),
    )
}
