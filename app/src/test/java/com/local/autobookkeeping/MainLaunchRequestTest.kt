package com.local.autobookkeeping

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainLaunchRequestTest {
    private val codec = ReviewRequestCodec()

    @Test
    fun `valid review wins over pending and intent is consumed once`() {
        val review = ReviewRequest(listOf(RecognitionChoice(candidate(), matchTransactionId = 73)))
        val intent = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.reviewIntentData(DIGEST)))
            .putExtra(EXTRA_REVIEW_REQUEST, codec.encode(review))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 91L)

        assertEquals(MainLaunchRequest.Review(review), consumeMainLaunchRequest(intent, codec))
        assertNull(consumeMainLaunchRequest(intent, codec))
        assertNull(intent.data)
        assertEquals(false, intent.hasExtra(EXTRA_REVIEW_REQUEST))
        assertEquals(false, intent.hasExtra(EXTRA_PENDING_TRANSACTION_ID))
    }

    @Test
    fun `pending launch requires a positive id with matching identity`() {
        val valid = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(73)))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 73L)
        val mismatched = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(74)))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 73L)
        val nonpositive = Intent()
            .setData(Uri.parse("autobookkeeping://recognition/pending/0"))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 0L)

        assertEquals(MainLaunchRequest.Pending(73), consumeMainLaunchRequest(valid, codec))
        assertNull(consumeMainLaunchRequest(mismatched, codec))
        assertNull(consumeMainLaunchRequest(nonpositive, codec))
    }

    @Test
    fun `invalid review safely falls back to a valid pending launch`() {
        val intent = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(73)))
            .putExtra(EXTRA_REVIEW_REQUEST, "{\"options\":[]}")
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 73L)

        assertEquals(MainLaunchRequest.Pending(73), consumeMainLaunchRequest(intent, codec))
        assertNull(consumeMainLaunchRequest(intent, codec))
    }

    @Test
    fun `saved launch opens ledger and is consumed once`() {
        val intent = Intent().setData(Uri.parse(SAVED_INTENT_DATA))

        assertEquals(MainLaunchRequest.Saved, consumeMainLaunchRequest(intent, codec))
        assertNull(consumeMainLaunchRequest(intent, codec))
    }

    @Test
    fun `activity recreation does not replay its original launch intent`() {
        val freshIntent = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(73)))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 73L)
        val recreatedIntent = Intent()
            .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(74)))
            .putExtra(EXTRA_PENDING_TRANSACTION_ID, 74L)

        assertEquals(
            MainLaunchRequest.Pending(73),
            consumeInitialMainLaunchRequest(freshIntent, savedInstanceState = null, codec),
        )
        assertNull(
            consumeInitialMainLaunchRequest(recreatedIntent, Bundle(), codec),
        )
    }

    @Test
    fun `review outcomes use stable destinations and short messages`() {
        assertEquals(
            ReviewResolution.OpenPending(73),
            RecognitionOutcome.Saved(transactionId = 73, pending = true).toReviewResolution(),
        )
        assertEquals(
            ReviewResolution.OpenLedger,
            RecognitionOutcome.Saved(transactionId = 74, pending = false).toReviewResolution(),
        )
        assertEquals(
            ReviewResolution.Message("该记录已处理"),
            RecognitionOutcome.Duplicate.toReviewResolution(),
        )
        assertEquals(
            ReviewResolution.Message("无法完成识别"),
            RecognitionOutcome.Rejected(RecognitionRejectReason.INCOMPLETE_FIELDS)
                .toReviewResolution(),
        )
        val choices = listOf(RecognitionChoice(candidate(), matchTransactionId = 73))
        assertEquals(
            ReviewResolution.SelectAgain(ReviewRequest(choices)),
            RecognitionOutcome.NeedsSelection(choices).toReviewResolution(),
        )
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
