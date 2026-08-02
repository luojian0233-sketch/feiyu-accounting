package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecognitionDebouncerTest {
    private val debouncer = RecognitionDebouncer(windowMillis = 1_000, maxEntries = 8)

    @Test
    fun `in flight normalized page cannot reserve concurrently`() {
        val first = debouncer.reserve(page("微信  支付", "¥ 1.00"), 5_000)

        assertNotNull(first)
        assertNull(debouncer.reserve(page(" 微信 支付 ", "¥ 1.00"), 5_500))
    }

    @Test
    fun `released reservation can retry immediately`() {
        val first = requireNotNull(debouncer.reserve(page("微信支付"), 5_000))

        debouncer.release(first)

        assertNotNull(debouncer.reserve(page("微信支付"), 5_001))
    }

    @Test
    fun `committed page is blocked only inside debounce window`() {
        val first = requireNotNull(debouncer.reserve(page("微信支付"), 5_000))
        debouncer.commit(first, 5_000)

        assertNull(debouncer.reserve(page("微信支付"), 5_999))
        assertNotNull(debouncer.reserve(page("微信支付"), 6_000))
    }

    @Test
    fun `geometry remains part of normalized page identity`() {
        assertNotNull(debouncer.reserve(page("微信支付", top = 0), 5_000))
        assertNotNull(debouncer.reserve(page("微信支付", top = 60), 5_100))
    }

    @Test
    fun `expired committed digests are removed when clock advances`() {
        val first = requireNotNull(debouncer.reserve(page("旧账单"), 0))
        debouncer.commit(first, 0)

        assertNotNull(debouncer.reserve(page("新账单"), 1_000))
        assertNotNull(debouncer.reserve(page("旧账单"), 1_000))
    }

    @Test
    fun `committed digest storage stays within hard limit`() {
        val bounded = RecognitionDebouncer(windowMillis = 10_000, maxEntries = 2)
        listOf("账单一", "账单二", "账单三").forEachIndexed { index, text ->
            val reservation = requireNotNull(bounded.reserve(page(text), index.toLong()))
            bounded.commit(reservation, index.toLong())
        }

        assertNotNull(bounded.reserve(page("账单一"), 3))
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
