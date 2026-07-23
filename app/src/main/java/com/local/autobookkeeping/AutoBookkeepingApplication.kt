package com.local.autobookkeeping

import android.app.Application
import com.local.autobookkeeping.data.RoomLedgerRepository
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.notification.CandidateProcessor
import com.local.autobookkeeping.notification.InMemoryNotificationDiagnostics
import com.local.autobookkeeping.notification.NotificationRouter
import com.local.autobookkeeping.notification.PendingConfirmationNotifier

class AutoBookkeepingApplication : Application() {
    val applicationGraph: ApplicationGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ApplicationGraph(this)
    }
}

class ApplicationGraph(application: Application) {
    private val appContext = application.applicationContext
    private val database by lazy { AppDatabase.create(appContext) }
    val ledgerRepository by lazy { RoomLedgerRepository(database) }

    val settingsRepository by lazy { SettingsRepository(appContext) }
    val notificationDiagnostics = InMemoryNotificationDiagnostics()
    val notificationRouter by lazy {
        NotificationRouter(settingsRepository, diagnostics = notificationDiagnostics)
    }
    val candidateProcessor by lazy { CandidateProcessor(ledgerRepository) }
    val pendingConfirmationNotifier by lazy { PendingConfirmationNotifier(appContext) }
}
