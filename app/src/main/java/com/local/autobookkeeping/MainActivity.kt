package com.local.autobookkeeping

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.RecognitionConfirmationOperations
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.backup.BackupOperations
import com.local.autobookkeeping.ui.entry.EntryScreen
import com.local.autobookkeeping.ui.entry.EntryViewModel
import com.local.autobookkeeping.ui.home.HomeScreen
import com.local.autobookkeeping.ui.home.HomeViewModel
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
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import com.local.autobookkeeping.recognition.tile.TileAddResult
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.notification.PendingConfirmationNotifier
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val reviewCodec = ReviewRequestCodec()
    private var launchRequest by mutableStateOf<MainLaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchRequest = consumeMainLaunchRequest(intent, reviewCodec)
        val graph = (application as AutoBookkeepingApplication).applicationGraph
        setContent {
            AutoBookkeepingRoot(
                repository = graph.ledgerRepository,
                notificationPermission = graph.notificationPermissionProvider,
                settingsRepository = graph.settingsRepository,
                backupOperations = graph.backupManager,
                recognitionConfirmation = graph.recognitionStore,
                recognitionCoordinator = graph.recognitionCoordinator,
                launchRequest = launchRequest,
                onLaunchRequestConsumed = { launchRequest = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = consumeMainLaunchRequest(intent, reviewCodec)
    }
}

internal const val EXTRA_PENDING_TRANSACTION_ID = "pending_transaction_id"
internal const val EXTRA_REVIEW_REQUEST = "recognition_review_request"
internal const val SAVED_INTENT_DATA = "autobookkeeping://recognition/saved"

sealed interface MainLaunchRequest {
    data class Pending(val transactionId: Long) : MainLaunchRequest
    data class Review(val request: ReviewRequest) : MainLaunchRequest
    data object Saved : MainLaunchRequest
}

internal fun consumeMainLaunchRequest(
    intent: Intent?,
    codec: ReviewRequestCodec = ReviewRequestCodec(),
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
fun AutoBookkeepingRoot(
    repository: LedgerRepository,
    notificationPermission: NotificationPermissionProvider,
    settingsRepository: SettingsOperations? = null,
    backupOperations: BackupOperations? = null,
    recognitionConfirmation: RecognitionConfirmationOperations? = null,
    recognitionCoordinator: RecognitionCoordinator? = null,
    launchRequest: MainLaunchRequest? = null,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val navigator = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }
    val current = navigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automaticAccess = remember(context) { AutomaticBookkeepingAccess(context) }
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
        EntryViewModel(repository, settingsRepository, recognitionConfirmation)
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
                notificationAccess = automaticAccess::isNotificationAccessEnabled,
                accessibilityAccess = automaticAccess::isAccessibilityAccessEnabled,
                backupOperations = backupOperations,
            )
        }
    }
    var activeReview by remember { mutableStateOf<ReviewRequest?>(null) }
    var reviewProcessing by remember { mutableStateOf(false) }
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
        ?.collectAsStateWithLifecycle(initialValue = ThemeStyle.CLEAR_MINT)
        ?.value
        ?: ThemeStyle.CLEAR_MINT
    LaunchedEffect(launchRequest) {
        val requested = launchRequest ?: return@LaunchedEffect
        onLaunchRequestConsumed()
        when (requested) {
            is MainLaunchRequest.Pending -> {
                activeReview = null
                reviewProcessing = false
                val transaction = try {
                    repository.findById(requested.transactionId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
                if (transaction == null) {
                    snackbarHostState.showSnackbar("无法打开待核对记录")
                } else {
                    entryViewModel.edit(transaction)
                    navigator.openEntry()
                }
            }
            is MainLaunchRequest.Review -> {
                activeReview = requested.request
                reviewProcessing = false
            }
            MainLaunchRequest.Saved -> {
                activeReview = null
                reviewProcessing = false
                navigator.select(AppDestination.LEDGER)
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
    BackHandler(enabled = activeReview == null && navigator.canHandleBack) {
        if (
            navigator.isStatisticsDrilldownActive &&
            current == AppDestination.LEDGER
        ) {
            ledgerViewModel.closeStatisticsDrilldown()
        }
        navigator.handleBack()
    }

    AutoBookkeepingTheme(themeStyle) {
        val review = activeReview
        if (review != null) {
            RecognitionReviewScreen(
                request = review,
                processing = reviewProcessing,
                onChoiceSelected = { choice ->
                    if (!reviewProcessing) {
                        reviewProcessing = true
                        scope.launch {
                            val resolution = try {
                                recognitionCoordinator
                                    ?.resolve(choice)
                                    ?.toReviewResolution()
                                    ?: ReviewResolution.Message("无法完成识别")
                            } catch (cancellation: CancellationException) {
                                reviewProcessing = false
                                throw cancellation
                            } catch (_: Throwable) {
                                ReviewResolution.Message("无法完成识别")
                            }
                            when (resolution) {
                                is ReviewResolution.OpenPending -> {
                                    val transaction = try {
                                        repository.findById(resolution.transactionId)
                                    } catch (cancellation: CancellationException) {
                                        reviewProcessing = false
                                        throw cancellation
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    activeReview = null
                                    reviewProcessing = false
                                    if (transaction == null) {
                                        snackbarHostState.showSnackbar("无法打开待核对记录")
                                    } else {
                                        entryViewModel.edit(transaction)
                                        navigator.openEntry()
                                    }
                                }
                                ReviewResolution.OpenLedger -> {
                                    activeReview = null
                                    reviewProcessing = false
                                    navigator.select(AppDestination.LEDGER)
                                }
                                is ReviewResolution.SelectAgain -> {
                                    activeReview = resolution.request
                                    reviewProcessing = false
                                }
                                is ReviewResolution.Message -> {
                                    activeReview = null
                                    reviewProcessing = false
                                    snackbarHostState.showSnackbar(resolution.text)
                                }
                            }
                        }
                    }
                },
                onBack = {
                    if (!reviewProcessing) activeReview = null
                },
            )
            return@AutoBookkeepingTheme
        }
        Scaffold(
            modifier = Modifier.testTag("active-theme-${themeStyle.name}"),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = current == destination,
                            onClick = {
                                if (
                                    navigator.isStatisticsDrilldownActive &&
                                    destination != AppDestination.ENTRY
                                ) {
                                    ledgerViewModel.closeStatisticsDrilldown()
                                }
                                if (destination == AppDestination.ENTRY && current != AppDestination.ENTRY) {
                                    entryViewModel.startNew()
                                }
                                navigator.select(destination)
                            },
                            modifier = Modifier
                                .testTag("nav-${destination.name}")
                                .then(
                                    if (destination == AppDestination.ENTRY) {
                                        Modifier.semantics { contentDescription = "记一笔" }
                                    } else {
                                        Modifier
                                }
                                ),
                            icon = {
                                if (destination == AppDestination.ENTRY) {
                                    FlatAddIcon()
                                } else {
                                    Text(
                                        text = when (destination) {
                                        AppDestination.HOME -> "⌂"
                                        AppDestination.LEDGER -> "≡"
                                        AppDestination.STATISTICS -> "▥"
                                        AppDestination.SETTINGS -> "⚙"
                                        AppDestination.ENTRY -> ""
                                        },
                                        fontSize = 17.sp,
                                    )
                                }
                            },
                            label = {
                                if (destination != AppDestination.ENTRY) {
                                    Text(destination.label)
                                }
                            },
                        )
                    }
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
                            if (!openNotificationAccessSafely(AndroidNotificationSettingsLauncher(context))) {
                                settingsViewModel?.reportNotificationSettingsLaunchFailure()
                            }
                        },
                        onEdit = {
                            entryViewModel.edit(it)
                            navigator.openEntry()
                        },
                    )
                    AppDestination.LEDGER -> LedgerScreen(
                        state = ledgerState,
                        onFiltersChanged = ledgerViewModel::updateFilters,
                        onEdit = {
                            entryViewModel.edit(it)
                            navigator.openEntry()
                        },
                    )
                    AppDestination.ENTRY -> EntryScreen(
                        state = entryState,
                        onUpdate = entryViewModel::update,
                        onSave = { confirm ->
                            scope.launch {
                                if (entryViewModel.save(confirmPending = confirm).isSuccess) {
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
                                    if (navigator.isStatisticsDrilldownActive) {
                                        ledgerViewModel.closeStatisticsDrilldown()
                                    }
                                    navigator.select(AppDestination.LEDGER)
                                }
                            }
                        },
                        onDismissDelete = entryViewModel::dismissDelete,
                        onBack = { navigator.handleBack() },
                    )
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
                                if (!automaticAccess.openNotificationAccess()) {
                                    settingsViewModel.reportNotificationSettingsLaunchFailure()
                                }
                            },
                            onOpenAccessibility = {
                                if (!automaticAccess.openAccessibilitySettings()) {
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
                                    automaticAccess.requestAddQuickSettingsTile(handleTileResult),
                                )
                            },
                            onOpenBatteryOptimization = {
                                if (!automaticAccess.openBatteryOptimizationSettings()) {
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlatAddIcon() {
    val color = androidx.compose.material3.LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val length = size.minDimension * 0.28f
        val centerX = size.width / 2
        val centerY = size.height / 2
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(centerX - length, centerY),
            end = androidx.compose.ui.geometry.Offset(centerX + length, centerY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(centerX, centerY - length),
            end = androidx.compose.ui.geometry.Offset(centerX, centerY + length),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
