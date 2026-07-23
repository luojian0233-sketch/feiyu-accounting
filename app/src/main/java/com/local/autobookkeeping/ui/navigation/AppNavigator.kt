package com.local.autobookkeeping.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver as ComposeSaver
import androidx.compose.runtime.saveable.listSaver
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.StatisticsSelection
import java.time.LocalDate

class AppNavigator(initial: AppDestination = AppDestination.HOME) {
    var current by mutableStateOf(initial)
        private set

    private var entryReturnDestination: AppDestination = initial
    private var statisticsDrilldownActive: Boolean = false
    var statisticsSelection: StatisticsSelection? = null
        private set

    val isStatisticsDrilldownActive: Boolean
        get() = statisticsDrilldownActive

    val canHandleBack: Boolean
        get() = current == AppDestination.ENTRY ||
            (current == AppDestination.LEDGER && statisticsDrilldownActive)

    fun select(destination: AppDestination) {
        if (destination == AppDestination.ENTRY) {
            openEntry()
        } else {
            current = destination
            statisticsDrilldownActive = false
            statisticsSelection = statisticsSelection?.copy(category = null)
        }
    }

    fun openEntry() {
        if (current != AppDestination.ENTRY) entryReturnDestination = current
        current = AppDestination.ENTRY
    }

    fun updateStatisticsSelection(selection: StatisticsSelection) {
        val activeCategory = statisticsSelection?.category.takeIf { statisticsDrilldownActive }
        statisticsSelection = selection.copy(category = activeCategory)
    }

    fun openLedgerFromStatistics(selection: StatisticsSelection) {
        if (selection.category.isNullOrBlank()) {
            statisticsDrilldownActive = false
            statisticsSelection = selection.copy(category = null)
            current = AppDestination.STATISTICS
            return
        }
        statisticsSelection = selection
        current = AppDestination.LEDGER
        statisticsDrilldownActive = true
    }

    fun handleBack(): Boolean {
        return when {
            current == AppDestination.ENTRY -> {
                current = entryReturnDestination
                true
            }
            current == AppDestination.LEDGER && statisticsDrilldownActive -> {
                statisticsDrilldownActive = false
                statisticsSelection = statisticsSelection?.copy(category = null)
                current = AppDestination.STATISTICS
                true
            }
            else -> false
        }
    }

    fun saveableState(): List<String> = listOf(
        current.name,
        entryReturnDestination.name,
        statisticsDrilldownActive.toString(),
        statisticsSelection?.rangeMode?.name.orEmpty(),
        statisticsSelection?.range?.start?.toString().orEmpty(),
        statisticsSelection?.range?.endInclusive?.toString().orEmpty(),
        statisticsSelection?.direction?.name.orEmpty(),
        statisticsSelection?.category.orEmpty(),
    )

    companion object {
        val Saver: ComposeSaver<AppNavigator, Any> = listSaver(
            save = { navigator -> navigator.saveableState() },
            restore = ::restore,
        )

        fun restore(saved: List<String>): AppNavigator {
            val requestedCurrent = saved.getOrNull(0).toDestinationOrNull() ?: AppDestination.HOME
            val restoredReturn = saved.getOrNull(1).toDestinationOrNull() ?: AppDestination.HOME
            val requestedDrilldown = saved.getOrNull(2) == true.toString()
            val restoredSelection = saved.toStatisticsSelectionOrNull()
            val validDrilldown = requestedDrilldown &&
                requestedCurrent == AppDestination.LEDGER &&
                !restoredSelection?.category.isNullOrBlank()
            val restoredCurrent = if (requestedDrilldown && !validDrilldown) {
                if (requestedCurrent == AppDestination.LEDGER) {
                    AppDestination.STATISTICS
                } else {
                    AppDestination.HOME
                }
            } else {
                requestedCurrent
            }
            return AppNavigator(restoredCurrent).also {
                it.entryReturnDestination = restoredReturn
                it.statisticsDrilldownActive = validDrilldown
                it.statisticsSelection = if (validDrilldown) {
                    restoredSelection
                } else {
                    restoredSelection?.copy(category = null)
                }
            }
        }

        private fun String?.toDestinationOrNull(): AppDestination? =
            this?.let { name -> AppDestination.entries.firstOrNull { it.name == name } }

        private fun List<String>.toStatisticsSelectionOrNull(): StatisticsSelection? = try {
            val mode = RangeMode.valueOf(getOrNull(3) ?: return null)
            val start = LocalDate.parse(getOrNull(4) ?: return null)
            val end = LocalDate.parse(getOrNull(5) ?: return null)
            val direction = Direction.valueOf(getOrNull(6) ?: return null)
            StatisticsSelection(
                rangeMode = mode,
                range = DateRange(start, end, BOOKKEEPING_ZONE),
                direction = direction,
                category = getOrNull(7)?.takeIf(String::isNotBlank),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
