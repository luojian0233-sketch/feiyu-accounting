package com.local.autobookkeeping

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.recognition.ReviewRequestCodec

class RecognitionReviewLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward(intent)
    }

    private fun forward(intent: Intent?) {
        val reviewCodec = (application as AutoBookkeepingApplication)
            .applicationGraph
            .reviewRequestCodec
        consumeMainLaunchRequest(intent, reviewCodec)
            ?.toMainActivityIntent(this, reviewCodec)
            ?.let(::startActivity)
        finish()
    }
}

internal fun MainLaunchRequest.toMainActivityIntent(
    context: Context,
    codec: ReviewRequestCodec,
): Intent = Intent(context, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    .also { intent ->
        when (this) {
            is MainLaunchRequest.Pending -> intent
                .setData(Uri.parse(PendingConfirmationNotifier.pendingIntentData(transactionId)))
                .putExtra(EXTRA_PENDING_TRANSACTION_ID, transactionId)
            is MainLaunchRequest.Review -> intent
                .setData(
                    Uri.parse(
                        PendingConfirmationNotifier.reviewIntentData(
                            request.options.first().candidate.evidenceDigest,
                        ),
                    ),
                )
                .putExtra(EXTRA_REVIEW_REQUEST, codec.encode(request))
            MainLaunchRequest.Saved -> intent.setData(Uri.parse(SAVED_INTENT_DATA))
        }
    }
