package com.local.autobookkeeping.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorTest {
    @Test
    fun `entry back returns to destination that opened it`() {
        val navigator = AppNavigator(AppDestination.HOME)
        navigator.select(AppDestination.LEDGER)
        navigator.openEntry()

        assertTrue(navigator.handleBack())
        assertEquals(AppDestination.LEDGER, navigator.current)
        assertFalse(navigator.handleBack())
    }
}
