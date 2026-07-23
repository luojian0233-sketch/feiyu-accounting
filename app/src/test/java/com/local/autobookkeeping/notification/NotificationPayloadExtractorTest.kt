package com.local.autobookkeeping.notification

import android.app.Notification
import com.local.autobookkeeping.data.sanitizeBankPackages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

class NotificationPayloadExtractorTest {
    @Test
    fun `big text is preferred when nonblank`() {
        val payload = NotificationPayloadExtractor.extract(
            flags = 0,
            title = "title",
            bigText = "complete payment message",
            text = "short message",
        )

        assertEquals("complete payment message", payload?.body)
    }

    @Test
    fun `blank big text falls back to regular text`() {
        val payload = NotificationPayloadExtractor.extract(
            flags = 0,
            title = "title",
            bigText = "  ",
            text = "short message",
        )

        assertEquals("short message", payload?.body)
    }

    @Test
    fun `group summary is skipped`() {
        assertNull(
            NotificationPayloadExtractor.extract(
                flags = Notification.FLAG_GROUP_SUMMARY,
                title = "private",
                bigText = "private",
                text = "private",
            ),
        )
    }

    @Test
    fun `bank packages are trimmed and invalid entries rejected`() {
        val sanitized = sanitizeBankPackages(
            setOf(
                " com.bank.valid ",
                "com.bank.valid.evil ",
                "com..broken",
                "com.bank/Listener",
                "com bank.bad",
                "",
            ),
        )

        assertEquals(setOf("com.bank.valid", "com.bank.valid.evil"), sanitized)
    }

    @Test
    fun `confirmation copy is generic`() {
        val copy = PendingConfirmationNotifier.CONTENT_TITLE +
            PendingConfirmationNotifier.CONTENT_TEXT

        assertEquals("有一笔记录待确认请打开自记账查看", copy)
        assertTrue(
            listOf("¥", "merchant", "account", "com.tencent.mm", "private")
                .none(copy::contains),
        )
    }

    @Test
    fun `pending confirmation is posted after persistence and posting failure is isolated`() = runTest {
        val order = mutableListOf<String>()
        val pending = buildCandidate(
            notification = NotificationText(
                title = "private",
                body = "private",
                packageName = NotificationRouter.WECHAT_PACKAGE,
                postedAt = java.time.Instant.parse("2026-07-23T00:00:00Z"),
            ),
            amountMinor = 100,
            direction = null,
            confidence = 40,
            merchant = null,
            eventKind = PaymentEventKind.AMBIGUOUS,
        )

        val savedId = PendingConfirmationCoordinator.process(
            candidate = pending,
            persist = {
                order += "persist"
                42L
            },
            notifyPending = {
                order += "notify"
                error("posting unavailable")
            },
        )

        assertEquals(42L, savedId)
        assertEquals(listOf("persist", "notify"), order)
    }
}
