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

    @Test
    fun `saver restores entry and ledger return destination across recreation`() {
        val beforeRecreation = AppNavigator(AppDestination.HOME)
        beforeRecreation.select(AppDestination.LEDGER)
        beforeRecreation.openEntry()

        val restored = AppNavigator.restore(beforeRecreation.saveableState())

        assertEquals(AppDestination.ENTRY, restored.current)
        assertTrue(restored.handleBack())
        assertEquals(AppDestination.LEDGER, restored.current)
    }

    @Test
    fun `saver restores entry opened from home and falls back safely for bad enum names`() {
        val beforeRecreation = AppNavigator(AppDestination.HOME)
        beforeRecreation.openEntry()

        val restored = AppNavigator.restore(beforeRecreation.saveableState())
        val malformed = AppNavigator.restore(listOf("REMOVED_DESTINATION", "ALSO_REMOVED"))

        assertEquals(AppDestination.ENTRY, restored.current)
        assertTrue(restored.handleBack())
        assertEquals(AppDestination.HOME, restored.current)
        assertEquals(AppDestination.HOME, malformed.current)
        assertFalse(malformed.handleBack())
    }
}
