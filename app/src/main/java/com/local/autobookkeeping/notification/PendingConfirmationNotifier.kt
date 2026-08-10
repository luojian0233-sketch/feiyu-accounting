package com.local.autobookkeeping.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.autobookkeeping.EXTRA_PENDING_TRANSACTION_ID
import com.local.autobookkeeping.EXTRA_REVIEW_REQUEST
import com.local.autobookkeeping.RecognitionReviewLaunchActivity
import com.local.autobookkeeping.SAVED_INTENT_DATA
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec

class PendingConfirmationNotifier(
    context: Context,
    private val reviewCodec: ReviewRequestCodec = ReviewRequestCodec(),
) {
    private val appContext = context.applicationContext

    fun notifyPending(transactionId: Long) = notify(
        notificationTag = "pending-$transactionId",
        notificationId = pendingRequestCode(transactionId),
        title = CONTENT_TITLE,
        text = CONTENT_TEXT,
        category = NotificationCompat.CATEGORY_REMINDER,
        launchIntent = launchIntent(
            data = pendingIntentData(transactionId),
            extraName = EXTRA_PENDING_TRANSACTION_ID,
            extraValue = transactionId,
        ),
    )

    fun notifyReview(request: ReviewRequest) {
        val encoded = try {
            reviewCodec.encode(request)
        } catch (_: IllegalArgumentException) {
            return
        }
        val digest = request.options.first().candidate.evidenceDigest
        notify(
            notificationTag = "review-$digest",
            notificationId = reviewRequestCode(digest),
            title = REVIEW_CONTENT_TITLE,
            text = REVIEW_CONTENT_TEXT,
            category = NotificationCompat.CATEGORY_REMINDER,
            launchIntent = launchIntent(
                data = reviewIntentData(digest),
                extraName = EXTRA_REVIEW_REQUEST,
                extraValue = encoded,
            ),
        )
    }

    fun notifySaved() = notify(
        notificationTag = "saved",
        notificationId = SUCCESS_NOTIFICATION_ID,
        title = SUCCESS_CONTENT_TITLE,
        text = SUCCESS_CONTENT_TEXT,
        category = NotificationCompat.CATEGORY_STATUS,
        launchIntent = launchIntent(data = SAVED_INTENT_DATA),
    )

    private fun notify(
        notificationTag: String,
        notificationId: Int,
        title: String,
        text: String,
        category: String,
        launchIntent: Intent,
    ) {
        try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = NotificationManagerCompat.from(appContext)
            if (!manager.areNotificationsEnabled()) return
            createChannel()
            manager.notify(
                notificationTag,
                notificationId,
                buildNotification(title, text, category, launchIntent),
            )
        } catch (_: SecurityException) {
            // The pending row is already persisted; reminder delivery is optional.
        } catch (_: RuntimeException) {
            // Notification subsystem failures must never roll back bookkeeping.
        }
    }

    private fun createChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        text: String,
        category: String,
        launchIntent: Intent,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationRequestCode(launchIntent),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setCategory(category)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun launchIntent(
        data: String,
        extraName: String? = null,
        extraValue: Any? = null,
    ): Intent = Intent(appContext, RecognitionReviewLaunchActivity::class.java)
        .setData(Uri.parse(data))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .also { intent ->
            when (extraValue) {
                is Long -> intent.putExtra(checkNotNull(extraName), extraValue)
                is String -> intent.putExtra(checkNotNull(extraName), extraValue)
                null -> Unit
                else -> error("Unsupported notification launch payload")
            }
        }

    private fun notificationRequestCode(intent: Intent): Int = when {
        intent.hasExtra(EXTRA_PENDING_TRANSACTION_ID) ->
            pendingRequestCode(intent.getLongExtra(EXTRA_PENDING_TRANSACTION_ID, -1))
        intent.hasExtra(EXTRA_REVIEW_REQUEST) ->
            reviewRequestCode(checkNotNull(intent.data?.lastPathSegment))
        else -> SUCCESS_NOTIFICATION_ID
    }

    companion object {
        internal const val CONTENT_TITLE = "有一笔记录待确认"
        internal const val CONTENT_TEXT = "请打开自记账查看"
        internal const val REVIEW_CONTENT_TITLE = "请选择要核对的记录"
        internal const val REVIEW_CONTENT_TEXT = "发现多笔候选，请打开自记账选择"
        internal const val SUCCESS_CONTENT_TITLE = "自动记账已保存"
        internal const val SUCCESS_CONTENT_TEXT = "请打开自记账查看"
        private const val CHANNEL_ID = "pending_bookkeeping_confirmation"
        private const val CHANNEL_NAME = "自动记账状态"
        private const val CHANNEL_DESCRIPTION = "提醒自动记账保存或待确认状态"
        private const val SUCCESS_NOTIFICATION_ID = 1002
        private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

        internal fun pendingRequestCode(transactionId: Long): Int {
            require(transactionId > 0) { "Pending transaction id must be positive" }
            return (((transactionId - 1) % Int.MAX_VALUE) + 1).toInt()
        }

        internal fun pendingIntentData(transactionId: Long): String {
            require(transactionId > 0) { "Pending transaction id must be positive" }
            return "autobookkeeping://recognition/pending/$transactionId"
        }

        internal fun reviewRequestCode(digest: String): Int {
            require(digest.matches(LOWERCASE_SHA256)) { "Invalid recognition digest" }
            val prefix = digest.take(8).toLong(16)
            return ((prefix % Int.MAX_VALUE).takeIf { it > 0 } ?: 1).toInt()
        }

        internal fun reviewIntentData(digest: String): String {
            require(digest.matches(LOWERCASE_SHA256)) { "Invalid recognition digest" }
            return "autobookkeeping://recognition/review/$digest"
        }
    }
}

object PendingConfirmationCoordinator {
    suspend fun process(
        candidate: RecognitionCandidate,
        recognize: suspend (RecognitionCandidate) -> RecognitionOutcome,
        notifyPending: (Long) -> Unit,
        notifyReview: (ReviewRequest) -> Unit,
        notifySaved: () -> Unit,
    ): RecognitionOutcome {
        val outcome = recognize(candidate)
        val notify = when (outcome) {
            is RecognitionOutcome.Saved -> if (outcome.pending) {
                { notifyPending(outcome.transactionId) }
            } else {
                notifySaved
            }
            is RecognitionOutcome.NeedsSelection -> {
                { notifyReview(ReviewRequest(outcome.options)) }
            }
            RecognitionOutcome.Duplicate,
            is RecognitionOutcome.Rejected,
            -> null
        }
        try {
            notify?.invoke()
        } catch (_: RuntimeException) {
            // Persistence is authoritative; reminder delivery is best effort.
        }
        return outcome
    }
}
