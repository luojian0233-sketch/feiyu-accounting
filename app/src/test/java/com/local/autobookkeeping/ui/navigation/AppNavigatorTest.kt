package com.local.autobookkeeping.ui.navigation

import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.StatisticsSelection
import java.time.LocalDate
import java.time.ZoneId
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

    @Test
    fun `statistics drilldown back returns to statistics and survives recreation`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        val selection = statisticsSelection(category = "餐饮")
        navigator.updateStatisticsSelection(selection.copy(category = null))
        navigator.openLedgerFromStatistics(selection)

        val restored = AppNavigator.restore(navigator.saveableState())

        assertEquals(AppDestination.LEDGER, restored.current)
        assertEquals(selection, restored.statisticsSelection)
        assertTrue(restored.isStatisticsDrilldownActive)
        assertTrue(restored.canHandleBack)
        assertTrue(restored.handleBack())
        assertEquals(AppDestination.STATISTICS, restored.current)
        assertFalse(restored.isStatisticsDrilldownActive)
        assertEquals(selection.copy(category = null), restored.statisticsSelection)
    }

    @Test
    fun `malformed active drilldown restores safely to statistics`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        navigator.openLedgerFromStatistics(statisticsSelection(category = "餐饮"))
        val malformed = navigator.saveableState().toMutableList().also { saved ->
            saved[3] = "not-a-range-mode"
        }

        val restored = AppNavigator.restore(malformed)

        assertEquals(AppDestination.STATISTICS, restored.current)
        assertFalse(restored.isStatisticsDrilldownActive)
        assertFalse(restored.canHandleBack)
    }

    @Test
    fun `selecting normal ledger clears temporary drilldown navigation state`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        navigator.openLedgerFromStatistics(statisticsSelection(category = "餐饮"))

        navigator.select(AppDestination.LEDGER)

        assertEquals(AppDestination.LEDGER, navigator.current)
        assertFalse(navigator.isStatisticsDrilldownActive)
        assertEquals(null, navigator.statisticsSelection?.category)
        assertFalse(navigator.canHandleBack)
    }

    private fun statisticsSelection(category: String?) = StatisticsSelection(
        rangeMode = RangeMode.MONTH,
        range = DateRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-31"),
            ZoneId.of("Asia/Shanghai"),
        ),
        direction = Direction.INCOME,
        category = category,
    )
}
