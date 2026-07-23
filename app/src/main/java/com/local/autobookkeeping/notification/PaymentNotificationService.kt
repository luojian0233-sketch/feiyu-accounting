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

        serviceScope.launch {
            val graph = (application as? AutoBookkeepingApplication)?.applicationGraph ?: return@launch
            try {
                if (!graph.notificationRouter.isSourceEnabled(sourcePackage)) return@launch
                val notification = sbn.notification
                val extras = notification.extras
                val payload = NotificationPayloadExtractor.extract(
                    flags = notification.flags,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE),
                    bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT),
                ) ?: return@launch
                val input = NotificationText(
                    title = payload.title,
                    body = payload.body,
                    packageName = sourcePackage,
                    postedAt = Instant.ofEpochMilli(postedAt),
                )
                graph.notificationRouter.route(input)?.let { candidate ->
                    PendingConfirmationCoordinator.process(
                        candidate = candidate,
                        persist = graph.candidateProcessor::process,
                        notifyPending = graph.pendingConfirmationNotifier::notifyPending,
                    )
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
