package com.local.autobookkeeping

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.MerchantRuleOperations
import com.local.autobookkeeping.data.RecognitionConfirmationOperations
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.backup.BackupOperations
import com.local.autobookkeeping.ui.entry.CategorySelectorScreen
import com.local.autobookkeeping.ui.entry.EntryScreen
import com.local.autobookkeeping.ui.entry.applyAmountKey
import com.local.autobookkeeping.ui.entry.EntryViewModel
import com.local.autobookkeeping.ui.entry.defaultEntryCategoryChoices
import com.local.autobookkeeping.ui.home.HomeScreen
import com.local.autobookkeeping.ui.home.HomeViewModel
import com.local.autobookkeeping.data.update.AppVersion
import com.local.autobookkeeping.data.update.GithubUpdateChecker
import com.local.autobookkeeping.data.update.ApkVerifier
import com.local.autobookkeeping.data.update.HttpApkDownloader
import com.local.autobookkeeping.data.update.LocalApkInstaller
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider
import com.local.autobookkeeping.ui.ledger.LedgerScreen
import com.local.autobookkeeping.ui.ledger.LedgerViewModel
import com.local.autobookkeeping.ui.ledger.statisticsDrilldownFilter
import com.local.autobookkeeping.ui.ledger.toFilterState
import com.local.autobookkeeping.ui.navigation.AppDestination
import com.local.autobookkeeping.ui.navigation.AppNavigator
import com.local.autobookkeeping.ui.recognition.RecognitionReviewScreen
import com.local.autobookkeeping.ui.statistics.StatisticsScreen
import com.local.autobookkeeping.ui.statistics.StatisticsSelection
import com.local.autobookkeeping.ui.statistics.StatisticsViewModel
import com.local.autobookkeeping.ui.settings.SettingsScreen
import com.local.autobookkeeping.ui.settings.SettingsUiState
import com.local.autobookkeeping.ui.settings.SettingsViewModel
import com.local.autobookkeeping.ui.theme.AppBottomBar
import com.local.autobookkeeping.ui.theme.DEFAULT_THEME_STYLE
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import com.local.autobookkeeping.recognition.tile.TileAddResult
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var reviewCodec: ReviewRequestCodec
    private lateinit var launchViewModel: MainLaunchViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as AutoBookkeepingApplication).applicationGraph
        reviewCodec = graph.reviewRequestCodec
        launchViewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    MainLaunchViewModel(
                        savedStateHandle = createSavedStateHandle(),
                        reviewCodec = reviewCodec,
                        findById = graph.ledgerRepository::findById,
                        resolve = graph.recognitionCoordinator::resolve,
                    )
                }
            },
        )[MainLaunchViewModel::class.java]
        consumeInitialMainLaunchRequest(intent, savedInstanceState, reviewCodec)
            ?.let(launchViewModel::accept)
        setContent {
            AutoBookkeepingRoot(
                repository = graph.ledgerRepository,
                notificationPermission = graph.notificationPermissionProvider,
                settingsRepository = graph.settingsRepository,
                backupOperations = graph.backupManager,
                recognitionConfirmation = graph.recognitionStore,
                merchantRules = graph.recognitionStore,
                automaticBookkeepingAccess = graph.automaticBookkeepingAccess,
                mainLaunchViewModel = launchViewModel,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeMainLaunchRequest(intent, reviewCodec)?.let(launchViewModel::accept)
    }
}

internal const val EXTRA_PENDING_TRANSACTION_ID = "pending_transaction_id"
internal const val EXTRA_REVIEW_REQUEST = "recognition_review_request"
internal const val SAVED_INTENT_DATA = "autobookkeeping://recognition/saved"

internal fun consumeInitialMainLaunchRequest(
    intent: Intent?,
    savedInstanceState: Bundle?,
    codec: ReviewRequestCodec,
): MainLaunchRequest? = if (savedInstanceState == null) {
    consumeMainLaunchRequest(intent, codec)
} else {
    null
}

sealed interface MainLaunchRequest {
    data class Pending(val transactionId: Long) : MainLaunchRequest
    data class Review(val request: ReviewRequest) : MainLaunchRequest
    data object Saved : MainLaunchRequest
}

internal fun consumeMainLaunchRequest(
    intent: Intent?,
    codec: ReviewRequestCodec,
): MainLaunchRequest? {
    if (intent == null) return null
    val request = try {
        val data = intent.data?.toString()
        val review = intent.getStringExtra(EXTRA_REVIEW_REQUEST)
            ?.let(codec::decode)
            ?.takeIf { decoded ->
                data == PendingConfirmationNotifier.reviewIntentData(
                    decoded.options.first().candidate.evidenceDigest,
                )
            }
        if (review != null) {
            MainLaunchRequest.Review(review)
        } else {
            val pendingId = intent
                .takeIf { it.hasExtra(EXTRA_PENDING_TRANSACTION_ID) }
                ?.getLongExtra(EXTRA_PENDING_TRANSACTION_ID, -1)
            when {
                pendingId != null &&
                    pendingId > 0 &&
                    data == PendingConfirmationNotifier.pendingIntentData(pendingId) ->
                    MainLaunchRequest.Pending(pendingId)
                data == SAVED_INTENT_DATA -> MainLaunchRequest.Saved
                else -> null
            }
        }
    } catch (_: RuntimeException) {
        null
    }
    intent.removeExtra(EXTRA_REVIEW_REQUEST)
    intent.removeExtra(EXTRA_PENDING_TRANSACTION_ID)
    intent.data = null
    return request
}

internal sealed interface ReviewResolution {
    data class OpenPending(val transactionId: Long) : ReviewResolution
    data object OpenLedger : ReviewResolution
    data class SelectAgain(val request: ReviewRequest) : ReviewResolution
    data class Message(val text: String) : ReviewResolution
}

internal fun RecognitionOutcome.toReviewResolution(): ReviewResolution = when (this) {
    is RecognitionOutcome.Saved -> if (pending) {
        ReviewResolution.OpenPending(transactionId)
    } else {
        ReviewResolution.OpenLedger
    }
    is RecognitionOutcome.NeedsSelection -> try {
        ReviewResolution.SelectAgain(ReviewRequest(options))
    } catch (_: IllegalArgumentException) {
        ReviewResolution.Message("无法完成识别")
    }
    RecognitionOutcome.Duplicate -> ReviewResolution.Message("该记录已处理")
    is RecognitionOutcome.Rejected -> ReviewResolution.Message("无法完成识别")
}

@Composable
internal fun AutoBookkeepingRoot(
    repository: LedgerRepository,
    notificationPermission: NotificationPermissionProvider,
    settingsRepository: SettingsOperations? = null,
    backupOperations: BackupOperations? = null,
    recognitionConfirmation: RecognitionConfirmationOperations? = null,
    merchantRules: MerchantRuleOperations? = null,
    automaticBookkeepingAccess: AutomaticBookkeepingAccess? = null,
    mainLaunchViewModel: MainLaunchViewModel? = null,
) {
    val navigator = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }
    val current = navigator.current
    var categorySelectorOpen by rememberSaveable { mutableStateOf(false) }
    var amountKeypadOpen by rememberSaveable { mutableStateOf(false) }
    val isCategorySelector = current == AppDestination.ENTRY && categorySelectorOpen
    // 局内键盘展开时让位给键盘，底栏收起
    val isAmountKeypadOpen = current == AppDestination.ENTRY && amountKeypadOpen
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automaticAccess = automaticBookkeepingAccess
    val homeViewModel = viewModel {
        HomeViewModel(
            repository = repository,
            notificationPermission = notificationPermission,
        )
    }
    val ledgerViewModel = viewModel {
        val restored = navigator.statisticsSelection
        val restoredOrdinaryFilters = navigator.ledgerFilterSnapshot?.toFilterState()
        val restoredFilter = if (
            navigator.isStatisticsDrilldownActive &&
            !restored?.category.isNullOrBlank()
        ) {
            val selection = checkNotNull(restored)
            statisticsDrilldownFilter(
                category = checkNotNull(selection.category),
                range = selection.range,
                direction = selection.direction,
            )
        } else {
            null
        }
        LedgerViewModel(
            repository = repository,
            initialStatisticsDrilldown = restoredFilter,
            initialOrdinaryFilters = restoredOrdinaryFilters
                ?: com.local.autobookkeeping.ui.ledger.LedgerFilterState(),
        )
    }
    val entryViewModel = viewModel {
        EntryViewModel(repository, settingsRepository, recognitionConfirmation, merchantRules)
    }
    val statisticsViewModel = viewModel {
        StatisticsViewModel(
            repository = repository,
            initialSelection = navigator.statisticsSelection?.copy(category = null),
        )
    }
    val settingsViewModel = settingsRepository?.let { settings ->
        viewModel<SettingsViewModel> {
            SettingsViewModel(
                repository = settings,
                notificationAccess = {
                    automaticAccess?.isNotificationAccessEnabled() == true
                },
                accessibilityAccess = {
                    automaticAccess?.isAccessibilityAccessEnabled() == true
                },
                backupOperations = backupOperations,
                updateChecker = GithubUpdateChecker(BuildConfig.UPDATE_REPO),
                apkDownloader = HttpApkDownloader(context.cacheDir),
                apkVerifier = ApkVerifier { apk -> LocalApkInstaller.verify(context, apk) },
                currentVersion = AppVersion(
                    name = BuildConfig.VERSION_NAME,
                    code = BuildConfig.VERSION_CODE,
                ),
            )
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRestoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = backupCreateDocumentContract(),
    ) { uri ->
        if (uri != null && settingsViewModel != null) {
            scope.launch {
                settingsViewModel.exportBackup {
                    checkNotNull(
                        withBackupOutputStreamOnIo(
                            open = {
                                context.contentResolver.openOutputStream(uri, "wt")
                            },
                        ) { output ->
                            checkNotNull(backupOperations).export(output)
                        },
                    ) { "无法打开备份输出流" }
                }
            }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = backupOpenDocumentContract(),
    ) { uri ->
        if (uri != null && settingsViewModel != null) {
            pendingRestoreUri = uri.toString()
            settingsViewModel.requestBackupRestoreConfirmation()
        }
    }
    // 从本地安装包更新：选文件 → 复制到缓存 → 验包名/签名/版本 → 交给系统安装器。
    val localApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val viewModel = settingsViewModel
        if (uri != null && viewModel != null) {
            viewModel.beginLocalUpdate()
            scope.launch {
                when (val prepared = LocalApkInstaller.prepare(context, uri)) {
                    is LocalApkInstaller.Prepared.Installable -> {
                        val launched = runCatching {
                            context.startActivity(
                                LocalApkInstaller.installIntent(context, prepared.apk),
                            )
                        }.isSuccess
                        if (launched) {
                            viewModel.reportLocalUpdateReady(
                                prepared.versionName,
                                prepared.versionCode,
                            )
                        } else {
                            viewModel.reportLocalUpdateFailure("没有找到能安装 APK 的系统组件")
                        }
                    }

                    is LocalApkInstaller.Prepared.Rejected ->
                        viewModel.reportLocalUpdateRejected(prepared.message)

                    is LocalApkInstaller.Prepared.Failed ->
                        viewModel.reportLocalUpdateFailure(prepared.message)
                }
            }
        }
    }
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val ledgerState by ledgerViewModel.state.collectAsStateWithLifecycle()
    val entryState by entryViewModel.state.collectAsStateWithLifecycle()
    val statisticsState by statisticsViewModel.state.collectAsStateWithLifecycle()
    val settingsState = settingsViewModel
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
        ?: SettingsUiState()
    val themeStyle = settingsRepository
        ?.theme
        ?.collectAsStateWithLifecycle(initialValue = DEFAULT_THEME_STYLE)
        ?.value
        ?: DEFAULT_THEME_STYLE
    val mainLaunchState = mainLaunchViewModel
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
        ?: MainLaunchUiState.Idle
    LaunchedEffect(mainLaunchState) {
        when (val launch = mainLaunchState) {
            MainLaunchUiState.Idle -> Unit
            is MainLaunchUiState.Pending -> mainLaunchViewModel?.loadPending(launch.token)
            is MainLaunchUiState.Review -> Unit
            is MainLaunchUiState.OpenPending -> {
                categorySelectorOpen = false
                entryViewModel.edit(launch.transaction)
                navigator.openEntry()
                mainLaunchViewModel?.acknowledge(launch.token)
            }
            is MainLaunchUiState.OpenLedger -> {
                navigator.select(AppDestination.LEDGER)
                mainLaunchViewModel?.acknowledge(launch.token)
            }
            is MainLaunchUiState.Message -> {
                snackbarHostState.showSnackbar(launch.text)
                mainLaunchViewModel?.acknowledge(launch.token)
            }
        }
    }
    LaunchedEffect(
        statisticsState.rangeMode,
        statisticsState.range,
        statisticsState.direction,
    ) {
        navigator.updateStatisticsSelection(
            StatisticsSelection(
                rangeMode = statisticsState.rangeMode,
                range = statisticsState.range,
                direction = statisticsState.direction,
            ),
        )
    }
    LaunchedEffect(
        ledgerState.filters,
        navigator.isStatisticsDrilldownActive,
    ) {
        if (!navigator.isStatisticsDrilldownActive) {
            navigator.updateLedgerFilterSnapshot(ledgerViewModel.ordinaryFilterSnapshot())
        }
    }
    LaunchedEffect(current) {
        if (current != AppDestination.ENTRY) {
            categorySelectorOpen = false
            amountKeypadOpen = false
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, homeViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) homeViewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycleOwner, settingsViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsViewModel?.refreshAutomaticAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activeReview = mainLaunchState as? MainLaunchUiState.Review
    BackHandler(
        enabled = activeReview == null &&
            (isCategorySelector || isAmountKeypadOpen || navigator.canHandleBack),
    ) {
        when {
            isCategorySelector -> categorySelectorOpen = false
            isAmountKeypadOpen -> amountKeypadOpen = false
            navigator.isStatisticsDrilldownActive && current == AppDestination.LEDGER -> {
                ledgerViewModel.closeStatisticsDrilldown()
                navigator.handleBack()
            }
            else -> navigator.handleBack()
        }
    }

    AutoBookkeepingTheme(themeStyle) {
        if (activeReview != null) {
            RecognitionReviewScreen(
                request = activeReview.request,
                processing = activeReview.processing,
                onChoiceSelected = { choice ->
                    mainLaunchViewModel?.selectReview(activeReview.token, choice)
                },
                onBack = {
                    if (!activeReview.processing) {
                        mainLaunchViewModel?.acknowledge(activeReview.token)
                    }
                },
            )
            return@AutoBookkeepingTheme
        }
        Scaffold(
            modifier = Modifier.testTag("active-theme-${themeStyle.name}"),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isCategorySelector && !isAmountKeypadOpen) {
                    AppBottomBar(
                        current = current,
                        onSelect = { destination ->
                            categorySelectorOpen = false
                            amountKeypadOpen = false
                            if (
                                navigator.isStatisticsDrilldownActive &&
                                destination != AppDestination.ENTRY
                            ) {
                                ledgerViewModel.closeStatisticsDrilldown()
                            }
                            if (
                                destination == AppDestination.ENTRY &&
                                current != AppDestination.ENTRY
                            ) {
                                entryViewModel.startNew()
                            }
                            navigator.select(destination)
                        },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("screen-${current.label}"),
            ) {
                when (current) {
                    AppDestination.HOME -> HomeScreen(
                        state = homeState,
                        onOpenPending = {
                            ledgerViewModel.updateFilters {
                                it.copy(query = "", month = null, pendingOnly = true)
                            }
                            navigator.select(AppDestination.LEDGER)
                        },
                        onOpenNotificationSettings = {
                            if (automaticAccess?.openNotificationAccess() != true) {
                                settingsViewModel?.reportNotificationSettingsLaunchFailure()
                            }
                        },
                        onEdit = {
                            categorySelectorOpen = false
                            entryViewModel.edit(it)
                            navigator.openEntry()
                        },
                    )
                    AppDestination.LEDGER -> LedgerScreen(
                        state = ledgerState,
                        onFiltersChanged = ledgerViewModel::updateFilters,
                        onEdit = {
                            categorySelectorOpen = false
                            entryViewModel.edit(it)
                            navigator.openEntry()
                        },
                        onDelete = ledgerViewModel::deleteTransactions,
                        onConfirm = ledgerViewModel::confirmTransactions,
                    )
                    AppDestination.ENTRY -> {
                        if (categorySelectorOpen) {
                            CategorySelectorScreen(
                                choices = if (entryState.configurationManaged) {
                                    entryState.categoryChoices
                                } else {
                                    defaultEntryCategoryChoices()
                                },
                                selectedCategory = entryState.draft.category,
                                loading = entryState.configurationManaged &&
                                    !entryState.categoryChoicesLoaded,
                                onSelect = { category ->
                                    entryViewModel.update { it.copy(category = category) }
                                    categorySelectorOpen = false
                                },
                                onBack = { categorySelectorOpen = false },
                            )
                        } else {
                            EntryScreen(
                                state = entryState,
                                onUpdate = entryViewModel::update,
                                onOpenCategorySelector = {
                                    amountKeypadOpen = false
                                    categorySelectorOpen = true
                                },
                                onSave = { confirm ->
                                    scope.launch {
                                        if (entryViewModel.save(confirmPending = confirm).isSuccess) {
                                            categorySelectorOpen = false
                                            if (navigator.isStatisticsDrilldownActive) {
                                                ledgerViewModel.closeStatisticsDrilldown()
                                            }
                                            navigator.select(AppDestination.LEDGER)
                                        }
                                    }
                                },
                                onRequestDelete = entryViewModel::requestDelete,
                                onConfirmDelete = {
                                    scope.launch {
                                        if (entryViewModel.delete().isSuccess) {
                                            categorySelectorOpen = false
                                            if (navigator.isStatisticsDrilldownActive) {
                                                ledgerViewModel.closeStatisticsDrilldown()
                                            }
                                            navigator.select(AppDestination.LEDGER)
                                        }
                                    }
                                },
                                onDismissDelete = entryViewModel::dismissDelete,
                                keypadOpen = amountKeypadOpen,
                                onKeypadOpenChange = { amountKeypadOpen = it },
                                onAmountKey = { key ->
                                    entryViewModel.update {
                                        it.copy(amountText = applyAmountKey(it.amountText, key))
                                    }
                                },
                                onOpenPageRecognition = {
                                    amountKeypadOpen = false
                                    automaticAccess?.openAccessibilitySettings()
                                },
                                onBack = {
                                    categorySelectorOpen = false
                                    navigator.handleBack()
                                },
                            )
                        }
                    }
                    AppDestination.STATISTICS -> StatisticsScreen(
                        state = statisticsState,
                        onModeSelected = statisticsViewModel::selectMode,
                        onDirectionSelected = statisticsViewModel::selectDirection,
                        onMoveRange = statisticsViewModel::moveRange,
                        onDateSelected = statisticsViewModel::selectDate,
                        onCustomRangeSelected = { start, end ->
                            statisticsViewModel.selectCustom(start, end)
                        },
                        onCategoryClick = { category ->
                            val selection = StatisticsSelection(
                                rangeMode = statisticsState.rangeMode,
                                range = statisticsState.range,
                                direction = statisticsState.direction,
                                category = category.category,
                            )
                            ledgerViewModel.openStatisticsDrilldown(
                                statisticsDrilldownFilter(
                                    category = category.category,
                                    range = statisticsState.range,
                                    direction = statisticsState.direction,
                                ),
                            )
                            navigator.openLedgerFromStatistics(
                                selection,
                                ledgerViewModel.ordinaryFilterSnapshot(),
                            )
                        },
                    )
                    AppDestination.SETTINGS -> if (settingsViewModel == null) {
                        PlaceholderScreen("设置")
                    } else {
                        SettingsScreen(
                            state = settingsState,
                            onThemeSelected = { scope.launch { settingsViewModel.setTheme(it) } },
                            onAutoChanged = {
                                scope.launch { settingsViewModel.setAutoBookkeepingEnabled(it) }
                            },
                            onWechatChanged = { scope.launch { settingsViewModel.setWechatEnabled(it) } },
                            onAlipayChanged = { scope.launch { settingsViewModel.setAlipayEnabled(it) } },
                            onAutoSaveChanged = {
                                scope.launch { settingsViewModel.setAutoSaveHighConfidence(it) }
                            },
                            onBankDraftChanged = settingsViewModel::updateBankPackageDraft,
                            onSaveBankPackages = { scope.launch { settingsViewModel.saveBankPackages() } },
                            onOpenNotificationAccess = {
                                if (automaticAccess?.openNotificationAccess() != true) {
                                    settingsViewModel.reportNotificationSettingsLaunchFailure()
                                }
                            },
                            onOpenAccessibility = {
                                if (automaticAccess?.openAccessibilitySettings() != true) {
                                    settingsViewModel.reportAutomaticSettingsLaunchFailure()
                                }
                            },
                            onAddQuickSettingsTile = {
                                val handleTileResult: (TileAddResult) -> Unit = { result ->
                                    when (result) {
                                        TileAddResult.Failed ->
                                            settingsViewModel.reportQuickSettingsTileFailure()
                                        TileAddResult.ManualInstructions ->
                                            settingsViewModel.reportQuickSettingsTileManualInstructions()
                                        TileAddResult.Requested,
                                        TileAddResult.AlreadyAdded,
                                        -> Unit
                                    }
                                }
                                handleTileResult(
                                    automaticAccess?.requestAddQuickSettingsTile(handleTileResult)
                                        ?: TileAddResult.Failed,
                                )
                            },
                            onOpenBatteryOptimization = {
                                if (automaticAccess?.openBatteryOptimizationSettings() != true) {
                                    settingsViewModel.reportAutomaticSettingsLaunchFailure()
                                }
                            },
                            onAddCategory = { scope.launch { settingsViewModel.addCategory(it) } },
                            onRenameCategory = { id, name ->
                                scope.launch { settingsViewModel.renameCategory(id, name) }
                            },
                            onMoveCategory = { id, offset ->
                                scope.launch { settingsViewModel.moveCategory(id, offset) }
                            },
                            onCategoryHiddenChanged = { id, hidden ->
                                scope.launch { settingsViewModel.setCategoryHidden(id, hidden) }
                            },
                            onAddAccount = { scope.launch { settingsViewModel.addAccount(it) } },
                            onRenameAccount = { id, name ->
                                scope.launch { settingsViewModel.renameAccount(id, name) }
                            },
                            onMoveAccount = { id, offset ->
                                scope.launch { settingsViewModel.moveAccount(id, offset) }
                            },
                            onAccountHiddenChanged = { id, hidden ->
                                scope.launch { settingsViewModel.setAccountHidden(id, hidden) }
                            },
                            onRequestClear = settingsViewModel::requestClearLedger,
                            onConfirmFirstClear = settingsViewModel::confirmFirstClearLedger,
                            onConfirmFinalClear = {
                                scope.launch { settingsViewModel.confirmFinalClearLedger() }
                            },
                            onDismissClear = settingsViewModel::dismissClearLedger,
                            onExportBackup = {
                                exportBackupLauncher.launch("自动记账备份.json")
                            },
                            onImportBackup = {
                                importBackupLauncher.launch(arrayOf(BACKUP_MIME_TYPE))
                            },
                            onConfirmBackupRestore = {
                                val selected = pendingRestoreUri
                                pendingRestoreUri = null
                                if (selected != null) {
                                    scope.launch {
                                        settingsViewModel.confirmBackupRestore {
                                            checkNotNull(
                                                checkNotNull(backupOperations).restore(
                                                    openInput = {
                                                        context.contentResolver.openInputStream(
                                                            Uri.parse(selected),
                                                        )
                                                    },
                                                ),
                                            ) { "无法打开备份输入流" }
                                        }
                                    }
                                }
                            },
                            onDismissBackupRestore = {
                                pendingRestoreUri = null
                                settingsViewModel.dismissBackupRestore()
                            },
                            onCheckUpdate = settingsViewModel::checkForUpdate,
                            onDownloadAndInstall = { url ->
                                if (LocalApkInstaller.canInstallPackages(context)) {
                                    settingsViewModel.downloadAndInstall(url)
                                } else {
                                    settingsViewModel.reportInstallPermissionRequired()
                                    runCatching {
                                        context.startActivity(
                                            LocalApkInstaller.unknownSourcesSettingsIntent(context),
                                        )
                                    }
                                }
                            },
                            onInstallApk = { path ->
                                val started = runCatching {
                                    context.startActivity(
                                        LocalApkInstaller.installIntent(context, java.io.File(path)),
                                    )
                                }.isSuccess
                                if (started) {
                                    settingsViewModel.clearInstallState()
                                } else {
                                    settingsViewModel.reportInstallLaunchFailure()
                                }
                            },
                            onOpenDownload = { url ->
                                if (!openInBrowser(context, url)) {
                                    settingsViewModel.reportDownloadLaunchFailure()
                                }
                            },
                            onPickLocalApk = {
                                if (LocalApkInstaller.canInstallPackages(context)) {
                                    localApkLauncher.launch(
                                        arrayOf(
                                            LocalApkInstaller.APK_MIME_TYPE,
                                            "application/octet-stream",
                                        ),
                                    )
                                } else {
                                    settingsViewModel.reportLocalUpdatePermissionRequired()
                                    runCatching {
                                        context.startActivity(
                                            LocalApkInstaller.unknownSourcesSettingsIntent(context),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 网上的新版本交给浏览器下载。
 *
 * App 自己不下载远端 APK、不做断点续传、不常驻下载服务——代价只是用户要多点两下。
 * 另一条路是「从本地安装包更新」：用户自己选手机里的 APK，App 只做复制与校验，
 * 再把文件交给系统安装器（见 [LocalApkInstaller]）。
 */
private fun openInBrowser(context: android.content.Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: RuntimeException) {
    false
}
