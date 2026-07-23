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

class PendingConfirmationNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyPending() {
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
            manager.notify(NOTIFICATION_ID, buildNotification())
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

    private fun buildNotification(): Notification {
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
            .setContentTitle(CONTENT_TITLE)
            .setContentText(CONTENT_TEXT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        internal const val CONTENT_TITLE = "有一笔记录待确认"
        internal const val CONTENT_TEXT = "请打开自记账查看"
        private const val CHANNEL_ID = "pending_bookkeeping_confirmation"
        private const val CHANNEL_NAME = "待确认记账"
        private const val CHANNEL_DESCRIPTION = "提醒您确认自动识别的记账记录"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 1001
    }
}

object PendingConfirmationCoordinator {
    suspend fun process(
        candidate: PaymentCandidate,
        persist: suspend (PaymentCandidate) -> Long?,
        notifyPending: () -> Unit,
    ): Long? {
        val savedId = persist(candidate)
        if (savedId != null && CandidateProcessor.requiresConfirmation(candidate)) {
            try {
                notifyPending()
            } catch (_: RuntimeException) {
                // Persistence is authoritative; reminder delivery is best effort.
            }
        }
        return savedId
    }
}
