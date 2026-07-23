package com.local.autobookkeeping.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
}
