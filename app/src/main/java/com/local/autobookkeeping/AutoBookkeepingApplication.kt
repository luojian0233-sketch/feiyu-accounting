package com.local.autobookkeeping

import android.app.Application
import com.local.autobookkeeping.backup.AppDatabaseBackupStore
import com.local.autobookkeeping.backup.BackupManager
import com.local.autobookkeeping.data.RoomLedgerRepository
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.notification.CandidateProcessor
import com.local.autobookkeeping.notification.InMemoryNotificationDiagnostics
import com.local.autobookkeeping.notification.NotificationRouter
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.notification.NotificationAccess
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider

open class AutoBookkeepingApplication : Application() {
    val applicationGraph: ApplicationGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createApplicationGraph()
    }

    protected open fun createApplicationGraph(): ApplicationGraph =
        ApplicationGraph(this)
}

class ApplicationGraph(
    application: Application,
    private val databaseProvider: (Application) -> AppDatabase = { app ->
        AppDatabase.create(app)
    },
    private val settingsRepositoryProvider: (Application, AppDatabase) -> SettingsRepository =
        { app, database -> SettingsRepository(app, database) },
) {
    private val appContext = application.applicationContext
    private val database by lazy { databaseProvider(application) }
    val ledgerRepository by lazy { RoomLedgerRepository(database) }

    val settingsRepository by lazy { settingsRepositoryProvider(application, database) }
    val backupManager by lazy {
        BackupManager(AppDatabaseBackupStore(database, settingsRepository))
    }
    val notificationDiagnostics = InMemoryNotificationDiagnostics()
    val notificationRouter by lazy {
        NotificationRouter(settingsRepository, diagnostics = notificationDiagnostics)
    }
    val candidateProcessor by lazy { CandidateProcessor(ledgerRepository) }
    val pendingConfirmationNotifier by lazy { PendingConfirmationNotifier(appContext) }
    val notificationPermissionProvider = NotificationPermissionProvider {
        NotificationAccess.isEnabled(appContext)
    }
}
