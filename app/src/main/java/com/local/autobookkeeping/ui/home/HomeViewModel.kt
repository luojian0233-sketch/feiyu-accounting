package com.local.autobookkeeping.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import com.local.autobookkeeping.ui.ledger.HomeSummary
import com.local.autobookkeeping.ui.ledger.calculateHomeSummary
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

fun interface NotificationPermissionProvider {
    fun isEnabled(): Boolean
}

data class HomeUiState(
    val summary: HomeSummary = HomeSummary(0, 0, 0, 0, emptyList()),
    val notificationAccessEnabled: Boolean = false,
)

class HomeViewModel(
    repository: LedgerRepository,
    private val notificationPermission: NotificationPermissionProvider,
    private val clock: Clock = Clock.system(BOOKKEEPING_ZONE),
    private val zone: ZoneId = BOOKKEEPING_ZONE,
) : ViewModel() {
    private val permissionRefresh = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> = combine(
        repository.observeTransactions(),
        permissionRefresh,
    ) { rows, _ ->
        HomeUiState(
            summary = calculateHomeSummary(rows, clock.instant(), zone),
            notificationAccessEnabled = notificationPermission.isEnabled(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refreshPermission() {
        permissionRefresh.value += 1
    }
}
