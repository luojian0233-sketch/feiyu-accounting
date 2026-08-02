package com.local.autobookkeeping.notification

import android.app.Notification
import com.local.autobookkeeping.data.sanitizeBankPackages
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
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
            PendingConfirmationNotifier.CONTENT_TEXT +
            PendingConfirmationNotifier.SUCCESS_CONTENT_TITLE +
            PendingConfirmationNotifier.SUCCESS_CONTENT_TEXT

        assertEquals("有一笔记录待确认请打开自记账查看自动记账已保存请打开自记账查看", copy)
        assertTrue(
            listOf("¥", "merchant", "account", "com.tencent.mm", "private")
                .none(copy::contains),
        )
    }

    @Test
    fun `pending confirmation is posted after coordinator persistence and posting failure is isolated`() = runTest {
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

        val outcome = PendingConfirmationCoordinator.process(
            candidate = pending.toRecognitionCandidate(),
            recognize = {
                order += "persist"
                RecognitionOutcome.Saved(42L, pending = true)
            },
            notifyPending = {
                order += "notify"
                error("posting unavailable")
            },
            notifySaved = { error("success notification must not be used") },
        )

        assertEquals(RecognitionOutcome.Saved(42L, pending = true), outcome)
        assertEquals(listOf("persist", "notify"), order)
    }

    @Test
    fun `notification outcome posts only the matching private local reminder`() = runTest {
        val pending = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val candidate = buildCandidate(
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
        ).toRecognitionCandidate()
        val outcomes = listOf(
            RecognitionOutcome.Saved(1L, pending = true),
            RecognitionOutcome.NeedsSelection(listOf(RecognitionChoice(candidate))),
            RecognitionOutcome.Saved(2L, pending = false),
            RecognitionOutcome.Duplicate,
            RecognitionOutcome.Rejected(RecognitionRejectReason.INCOMPLETE_FIELDS),
        )

        outcomes.forEach { expected ->
            PendingConfirmationCoordinator.process(
                candidate = candidate,
                recognize = { expected },
                notifyPending = { pending += expected.toString() },
                notifySaved = { saved += expected.toString() },
            )
        }

        assertEquals(outcomes.take(2).map(Any::toString), pending)
        assertEquals(listOf(outcomes[2].toString()), saved)
    }
}
