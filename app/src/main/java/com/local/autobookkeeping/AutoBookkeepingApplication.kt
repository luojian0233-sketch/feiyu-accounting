package com.local.autobookkeeping

import android.app.Application
import com.local.autobookkeeping.backup.AppDatabaseBackupStore
import com.local.autobookkeeping.backup.BackupManager
import com.local.autobookkeeping.data.RoomRecognitionStore
import com.local.autobookkeeping.data.RoomLedgerRepository
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.notification.InMemoryNotificationDiagnostics
import com.local.autobookkeeping.notification.NotificationRouter
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.notification.NotificationAccess
import com.local.autobookkeeping.recognition.AutoSavePolicy
import com.local.autobookkeeping.recognition.AlipayPageParser
import com.local.autobookkeeping.recognition.GenericBillParser
import com.local.autobookkeeping.recognition.InMemoryRecognitionDiagnostics
import com.local.autobookkeeping.recognition.MerchantClassifier
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import com.local.autobookkeeping.recognition.WechatPageParser
import com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionController
import com.local.autobookkeeping.recognition.accessibility.SettingsAutomaticPageRecognitionSettings
import com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGateway
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import com.local.autobookkeeping.recognition.capture.MlKitTextRecognizer
import com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionController
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
    private val localTextRecognizerProvider: () -> LocalTextRecognizer = {
        MlKitTextRecognizer()
    },
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
    val recognitionDiagnostics = InMemoryRecognitionDiagnostics()
    val recognitionStore by lazy { RoomRecognitionStore(database) }
    val merchantClassifier by lazy { MerchantClassifier(recognitionStore) }
    val recognitionCoordinator by lazy {
        RecognitionCoordinator(
            store = recognitionStore,
            autoSavePolicy = AutoSavePolicy.failClosed(
                settingsRepository.autoSaveHighConfidence,
            ),
            classifier = merchantClassifier,
            diagnostics = recognitionDiagnostics,
        )
    }
    val accessibilityCaptureGateway = AccessibilityCaptureGateway()
    val localTextRecognizer by lazy { localTextRecognizerProvider() }
    val automaticPageRecognitionController by lazy {
        AutoPageRecognitionController(
            settings = SettingsAutomaticPageRecognitionSettings(settingsRepository),
            captureGateway = accessibilityCaptureGateway,
            textRecognizer = localTextRecognizer,
            coordinator = recognitionCoordinator,
            diagnostics = recognitionDiagnostics,
        )
    }
    val screenshotRecognitionController by lazy {
        ScreenshotRecognitionController(
            textRecognizer = localTextRecognizer,
            wechatParser = WechatPageParser,
            alipayParser = AlipayPageParser,
            genericParser = GenericBillParser,
            coordinator = recognitionCoordinator,
        )
    }
    val automaticBookkeepingAccess by lazy { AutomaticBookkeepingAccess(appContext) }
    val reviewRequestCodec by lazy { ReviewRequestCodec() }
    val pendingConfirmationNotifier by lazy {
        PendingConfirmationNotifier(appContext, reviewRequestCodec)
    }
    val notificationPermissionProvider = NotificationPermissionProvider {
        NotificationAccess.isEnabled(appContext)
    }
}
