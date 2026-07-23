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

    fun select(destination: AppDestination) {
        if (destination == AppDestination.ENTRY) {
            openEntry()
        } else {
            current = destination
        }
    }

    fun openEntry() {
        if (current != AppDestination.ENTRY) entryReturnDestination = current
        current = AppDestination.ENTRY
    }

    fun handleBack(): Boolean {
        if (current != AppDestination.ENTRY) return false
        current = entryReturnDestination
        return true
    }

    fun saveableState(): List<String> = listOf(current.name, entryReturnDestination.name)

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
            }
        }

        private fun String?.toDestinationOrNull(): AppDestination? =
            this?.let { name -> AppDestination.entries.firstOrNull { it.name == name } }
    }
}
