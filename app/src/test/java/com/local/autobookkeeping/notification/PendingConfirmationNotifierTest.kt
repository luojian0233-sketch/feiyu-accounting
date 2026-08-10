package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.ReviewRequest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingConfirmationNotifierTest {
    @Test
    fun `pending launch uses positive transaction id identity`() {
        assertEquals(73, PendingConfirmationNotifier.pendingRequestCode(73))
        assertEquals(
            "autobookkeeping://recognition/pending/73",
            PendingConfirmationNotifier.pendingIntentData(73),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PendingConfirmationNotifier.pendingRequestCode(0)
        }
    }

    @Test
    fun `review launch derives stable positive id and keeps full digest identity`() {
        val highBitDigest = "ffffffff" + "a".repeat(56)
        val samePrefixFirst = "12345678" + "a".repeat(56)
        val samePrefixSecond = "12345678" + "b".repeat(56)

        assertTrue(PendingConfirmationNotifier.reviewRequestCode(highBitDigest) > 0)
        assertEquals(
            PendingConfirmationNotifier.reviewRequestCode(samePrefixFirst),
            PendingConfirmationNotifier.reviewRequestCode(samePrefixSecond),
        )
        assertNotEquals(
            PendingConfirmationNotifier.reviewIntentData(samePrefixFirst),
            PendingConfirmationNotifier.reviewIntentData(samePrefixSecond),
        )
    }

    @Test
    fun `coordinator routes pending id and review choices to distinct callbacks`() = runTest {
        var pendingId: Long? = null
        var review: ReviewRequest? = null
        var savedCalls = 0

        PendingConfirmationCoordinator.process(
            candidate = candidate(),
            recognize = { RecognitionOutcome.Saved(transactionId = 91, pending = true) },
            notifyPending = { pendingId = it },
            notifyReview = { review = it },
            notifySaved = { savedCalls += 1 },
        )
        PendingConfirmationCoordinator.process(
            candidate = candidate(),
            recognize = {
                RecognitionOutcome.NeedsSelection(
                    listOf(RecognitionChoice(candidate(), matchTransactionId = 17)),
                )
            },
            notifyPending = { pendingId = it },
            notifyReview = { review = it },
            notifySaved = { savedCalls += 1 },
        )

        assertEquals(91, pendingId)
        assertEquals(17, review?.options?.single()?.matchTransactionId)
        assertEquals(0, savedCalls)
    }

    private fun candidate() = RecognitionCandidate(
        platform = PaymentPlatform.ALIPAY,
        channel = CaptureChannel.NOTIFICATION,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = 880,
        direction = Direction.EXPENSE,
        merchant = "便利店",
        account = null,
        occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
        confidence = 88,
        evidenceDigest = "ab".repeat(32),
    )
}
