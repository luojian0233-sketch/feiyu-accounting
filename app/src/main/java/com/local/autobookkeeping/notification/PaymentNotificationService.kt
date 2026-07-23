package com.local.autobookkeeping.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.local.autobookkeeping.AutoBookkeepingApplication
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentNotificationService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val sourcePackage = sbn.packageName ?: return
        val postedAt = sbn.postTime
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val body = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()

        serviceScope.launch {
            val graph = (application as? AutoBookkeepingApplication)?.applicationGraph ?: return@launch
            try {
                val input = NotificationText(
                    title = title,
                    body = body,
                    packageName = sourcePackage,
                    postedAt = Instant.ofEpochMilli(postedAt),
                )
                graph.notificationRouter.route(input)?.let { candidate ->
                    graph.candidateProcessor.process(candidate)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                graph.notificationDiagnostics.record(NotificationDiagnostic.PROCESSOR_FAILURE)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
