package com.local.autobookkeeping.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.autobookkeeping.MainActivity
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome

class PendingConfirmationNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyPending() = notify(
        notificationId = PENDING_NOTIFICATION_ID,
        title = CONTENT_TITLE,
        text = CONTENT_TEXT,
        category = NotificationCompat.CATEGORY_REMINDER,
    )

    fun notifySaved() = notify(
        notificationId = SUCCESS_NOTIFICATION_ID,
        title = SUCCESS_CONTENT_TITLE,
        text = SUCCESS_CONTENT_TEXT,
        category = NotificationCompat.CATEGORY_STATUS,
    )

    private fun notify(
        notificationId: Int,
        title: String,
        text: String,
        category: String,
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
            manager.notify(notificationId, buildNotification(title, text, category))
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

    private fun buildNotification(title: String, text: String, category: String): Notification {
        val launchIntent = (
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: Intent(appContext, MainActivity::class.java)
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE,
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

    companion object {
        internal const val CONTENT_TITLE = "有一笔记录待确认"
        internal const val CONTENT_TEXT = "请打开自记账查看"
        internal const val SUCCESS_CONTENT_TITLE = "自动记账已保存"
        internal const val SUCCESS_CONTENT_TEXT = "请打开自记账查看"
        private const val CHANNEL_ID = "pending_bookkeeping_confirmation"
        private const val CHANNEL_NAME = "自动记账状态"
        private const val CHANNEL_DESCRIPTION = "提醒自动记账保存或待确认状态"
        private const val PENDING_NOTIFICATION_ID = 1001
        private const val SUCCESS_NOTIFICATION_ID = 1002
        private const val REQUEST_CODE = 1001
    }
}

object PendingConfirmationCoordinator {
    suspend fun process(
        candidate: RecognitionCandidate,
        recognize: suspend (RecognitionCandidate) -> RecognitionOutcome,
        notifyPending: () -> Unit,
        notifySaved: () -> Unit,
    ): RecognitionOutcome {
        val outcome = recognize(candidate)
        val notify = when (outcome) {
            is RecognitionOutcome.Saved -> if (outcome.pending) notifyPending else notifySaved
            is RecognitionOutcome.NeedsSelection -> notifyPending
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
