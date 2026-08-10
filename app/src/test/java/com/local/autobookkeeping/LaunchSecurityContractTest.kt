package com.local.autobookkeeping

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LaunchSecurityContractTest {
    private val context = RuntimeEnvironment.getApplication() as Context

    @Test
    fun `external launcher drops forged pending and review payloads`() {
        val request = ReviewRequest(listOf(RecognitionChoice(candidate(), matchTransactionId = 73)))
        val forged = Intent(context, LauncherActivity::class.java)
            .setData(Uri.parse(PendingConfirmationNotifier.reviewIntentData(DIGEST)))
            .putExtra(EXTRA_REVIEW_REQUEST, ReviewRequestCodec().encode(request))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 73L)

        val activity = Robolectric.buildActivity(LauncherActivity::class.java, forged).setup().get()
        val forwarded = shadowOf(activity).nextStartedActivity

        assertEquals(ComponentName(context, MainActivity::class.java), forwarded.component)
        assertNull(forwarded.data)
        assertFalse(forwarded.hasExtra(EXTRA_REVIEW_REQUEST))
        assertFalse(forwarded.hasExtra(EXTRA_PENDING_TRANSACTION_ID))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `every notification content intent targets the non exported trampoline`() {
        val notifier = PendingConfirmationNotifier(context)
        notifier.notifyPending(73)
        notifier.notifyReview(ReviewRequest(listOf(RecognitionChoice(candidate(), 73))))
        notifier.notifySaved()

        val notifications = shadowOf(
            context.getSystemService(NotificationManager::class.java),
        ).allNotifications

        assertEquals(3, notifications.size)
        notifications.forEach { notification ->
            assertEquals(
                ComponentName(context, RecognitionReviewLaunchActivity::class.java),
                shadowOf(notification.contentIntent).savedIntent.component,
            )
        }
    }

    private fun candidate() = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.SCREENSHOT,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = 12_345,
        direction = Direction.EXPENSE,
        merchant = "中国电信",
        account = "招商银行",
        occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
        confidence = 87,
        evidenceDigest = DIGEST,
    )

    private companion object {
        val DIGEST = "ab".repeat(32)
    }
}
