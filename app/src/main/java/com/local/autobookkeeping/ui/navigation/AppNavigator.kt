package com.local.autobookkeeping.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver as ComposeSaver
import androidx.compose.runtime.saveable.listSaver

class AppNavigator(initial: AppDestination = AppDestination.HOME) {
    var current by mutableStateOf(initial)
        private set

    private var entryReturnDestination: AppDestination = initial
    private var statisticsDrilldownActive: Boolean = false

    val canHandleBack: Boolean
        get() = current == AppDestination.ENTRY ||
            (current == AppDestination.LEDGER && statisticsDrilldownActive)

    fun select(destination: AppDestination) {
        if (destination == AppDestination.ENTRY) {
            openEntry()
        } else {
            current = destination
            statisticsDrilldownActive = false
        }
    }

    fun openEntry() {
        if (current != AppDestination.ENTRY) entryReturnDestination = current
        current = AppDestination.ENTRY
    }

    fun openLedgerFromStatistics() {
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
    )

    companion object {
        val Saver: ComposeSaver<AppNavigator, Any> = listSaver(
            save = { navigator -> navigator.saveableState() },
            restore = ::restore,
        )

        fun restore(saved: List<String>): AppNavigator {
            val restoredCurrent = saved.getOrNull(0).toDestinationOrNull() ?: AppDestination.HOME
            val restoredReturn = saved.getOrNull(1).toDestinationOrNull() ?: AppDestination.HOME
            return AppNavigator(restoredCurrent).also {
                it.entryReturnDestination = restoredReturn
                it.statisticsDrilldownActive = saved.getOrNull(2).toBoolean()
            }
        }

        private fun String?.toDestinationOrNull(): AppDestination? =
            this?.let { name -> AppDestination.entries.firstOrNull { it.name == name } }
    }
}
