package com.local.autobookkeeping

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.local.autobookkeeping.ui.entry.EntryScreen
import com.local.autobookkeeping.ui.entry.EntryViewModel
import com.local.autobookkeeping.ui.home.HomeScreen
import com.local.autobookkeeping.ui.home.HomeViewModel
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider
import com.local.autobookkeeping.ui.ledger.LedgerScreen
import com.local.autobookkeeping.ui.ledger.LedgerViewModel
import com.local.autobookkeeping.ui.navigation.AppDestination
import com.local.autobookkeeping.ui.navigation.AppNavigator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as AutoBookkeepingApplication).applicationGraph
        setContent {
            AutoBookkeepingRoot(
                repository = graph.ledgerRepository,
                notificationPermission = graph.notificationPermissionProvider,
            )
        }
    }
}

@Composable
fun AutoBookkeepingRoot(
    repository: LedgerRepository,
    notificationPermission: NotificationPermissionProvider,
) {
    val navigator = remember { AppNavigator() }
    val current = navigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val homeViewModel = viewModel {
        HomeViewModel(
            repository = repository,
            notificationPermission = notificationPermission,
        )
    }
    val ledgerViewModel = viewModel { LedgerViewModel(repository) }
    val entryViewModel = viewModel { EntryViewModel(repository) }
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val ledgerState by ledgerViewModel.state.collectAsStateWithLifecycle()
    val entryState by entryViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, homeViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) homeViewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = current == AppDestination.ENTRY) {
        navigator.handleBack()
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF23866B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDF3EA),
            background = Color(0xFFFAFCFB),
            surface = Color(0xFFFAFCFB),
        ),
        typography = comfortableTypography(),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = current == destination,
                            onClick = {
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
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
                                    navigator.select(AppDestination.LEDGER)
                                }
                            }
                        },
                        onRequestDelete = entryViewModel::requestDelete,
                        onConfirmDelete = {
                            scope.launch {
                                if (entryViewModel.delete().isSuccess) {
                                    navigator.select(AppDestination.LEDGER)
                                }
                            }
                        },
                        onDismissDelete = entryViewModel::dismissDelete,
                        onBack = { navigator.handleBack() },
                    )
                    AppDestination.STATISTICS -> PlaceholderScreen("统计功能将在下一步完成")
                    AppDestination.SETTINGS -> PlaceholderScreen("设置与界面风格将在后续完成")
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

private fun comfortableTypography() = Typography(
    displayLarge = Typography().displayLarge.copy(fontSize = 40.sp),
    headlineLarge = Typography().headlineLarge.copy(fontSize = 26.sp),
    titleLarge = Typography().titleLarge.copy(fontSize = 21.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 17.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 13.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp),
)
