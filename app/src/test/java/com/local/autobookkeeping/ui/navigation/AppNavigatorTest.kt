package com.local.autobookkeeping.ui.navigation

import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.StatisticsSelection
import com.local.autobookkeeping.ui.ledger.LedgerFilterSnapshot
import java.time.LocalDate
import java.time.YearMonth
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

    @Test
    fun `malformed and inverted saved dates safely downgrade active drilldown`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        navigator.openLedgerFromStatistics(statisticsSelection(category = "餐饮"))
        val nonDate = navigator.saveableState().toMutableList().also { it[4] = "not-a-date" }
        val inverted = navigator.saveableState().toMutableList().also {
            it[4] = "2026-08-02"
            it[5] = "2026-08-01"
        }

        listOf(nonDate, inverted).forEach { saved ->
            val restored = AppNavigator.restore(saved)
            assertEquals(AppDestination.STATISTICS, restored.current)
            assertFalse(restored.isStatisticsDrilldownActive)
            assertEquals(null, restored.statisticsSelection)
        }
    }

    @Test
    fun `entry opened from active drilldown restores and returns to filtered ledger`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        val selection = statisticsSelection(category = "餐饮")
        navigator.openLedgerFromStatistics(selection)
        navigator.openEntry()

        val restored = AppNavigator.restore(navigator.saveableState())

        assertEquals(AppDestination.ENTRY, restored.current)
        assertTrue(restored.isStatisticsDrilldownActive)
        assertEquals(selection, restored.statisticsSelection)
        assertTrue(restored.handleBack())
        assertEquals(AppDestination.LEDGER, restored.current)
        assertTrue(restored.isStatisticsDrilldownActive)
        assertTrue(restored.handleBack())
        assertEquals(AppDestination.STATISTICS, restored.current)
    }

    @Test
    fun `pre-drilldown ledger filters survive process recreation exactly`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        val ordinary = LedgerFilterSnapshot(
            query = "午餐",
            month = YearMonth.of(2026, 7),
            direction = Direction.EXPENSE,
            category = "餐饮",
            account = "支付宝",
            dateRange = DateRange(
                LocalDate.parse("2026-07-10"),
                LocalDate.parse("2026-07-20"),
                ZoneId.of("Asia/Shanghai"),
            ),
            pendingOnly = true,
        )
        navigator.openLedgerFromStatistics(statisticsSelection(category = "餐饮"), ordinary)

        val restored = AppNavigator.restore(navigator.saveableState())

        assertEquals(ordinary, restored.ledgerFilterSnapshot)
        assertFalse(restored.ledgerFilterSnapshot!!.statisticsEligibleOnly)
    }

    @Test
    fun `malformed ordinary filter pieces are sanitized independently`() {
        val navigator = AppNavigator(AppDestination.STATISTICS)
        navigator.openLedgerFromStatistics(
            statisticsSelection(category = "餐饮"),
            LedgerFilterSnapshot(
                query = "午餐",
                month = YearMonth.of(2026, 7),
                direction = Direction.EXPENSE,
                category = "餐饮",
                account = "支付宝",
                dateRange = DateRange(
                    LocalDate.parse("2026-07-10"),
                    LocalDate.parse("2026-07-20"),
                    ZoneId.of("Asia/Shanghai"),
                ),
            ),
        )
        val malformed = navigator.saveableState().toMutableList().also {
            it[9] = "bad-month"
            it[10] = "bad-direction"
            it[13] = "bad-start"
            it[14] = "bad-end"
        }

        val restored = AppNavigator.restore(malformed)

        assertEquals("午餐", restored.ledgerFilterSnapshot?.query)
        assertEquals(null, restored.ledgerFilterSnapshot?.month)
        assertEquals(null, restored.ledgerFilterSnapshot?.direction)
        assertEquals(null, restored.ledgerFilterSnapshot?.dateRange)
        assertEquals("支付宝", restored.ledgerFilterSnapshot?.account)
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
