package com.local.autobookkeeping.domain

import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.statistics.StatisticsEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatisticsEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun tx(
        id: Long,
        cents: Long,
        direction: Direction,
        timestamp: String,
        internal: Boolean = false,
        pending: Boolean = false,
        category: String = "Transfer",
        currency: String = "CNY",
    ) = Transaction(
        id = id,
        amountMinor = cents,
        currency = currency,
        direction = direction,
        category = category,
        occurredAt = Instant.parse(timestamp),
        internalTransfer = internal,
        pendingConfirmation = pending,
    )

    @Test
    fun `same category can count as income and expense while internal transfer is excluded`() {
        val rows = listOf(
            tx(1, 10_000, Direction.INCOME, "2026-07-22T04:00:00Z"),
            tx(2, 4_000, Direction.EXPENSE, "2026-07-22T04:00:00Z"),
            tx(3, 50_000, Direction.INCOME, "2026-07-22T04:00:00Z", internal = true),
        )
        val range = DateRange(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22), zone)

        assertEquals(10_000, StatisticsEngine.summarize(rows, range, Direction.INCOME).totalMinor)
        assertEquals(4_000, StatisticsEngine.summarize(rows, range, Direction.EXPENSE).totalMinor)
    }

    @Test
    fun `pending placeholder direction is excluded from statistics`() {
        val rows = listOf(
            tx(1, 8_800, Direction.EXPENSE, "2026-07-22T04:00:00Z", pending = true),
            tx(2, 1_200, Direction.EXPENSE, "2026-07-22T04:00:00Z"),
        )

        assertEquals(1_200, StatisticsEngine.summarize(rows, dayRange(), Direction.EXPENSE).totalMinor)
    }

    @Test
    fun `range includes both local-day boundaries and excludes adjacent dates`() {
        val rows = listOf(
            tx(1, 100, Direction.EXPENSE, "2026-07-21T16:00:00Z"),
            tx(2, 200, Direction.EXPENSE, "2026-07-22T15:59:59Z"),
            tx(3, 400, Direction.EXPENSE, "2026-07-21T15:59:59Z"),
            tx(4, 800, Direction.EXPENSE, "2026-07-22T16:00:00Z"),
        )
        val range = DateRange(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22), zone)

        assertEquals(300, StatisticsEngine.summarize(rows, range, Direction.EXPENSE).totalMinor)
    }

    @Test
    fun `category accumulation throws instead of wrapping on overflow`() {
        val rows = listOf(
            tx(1, Long.MAX_VALUE, Direction.EXPENSE, "2026-07-22T04:00:00Z"),
            tx(2, 1, Direction.EXPENSE, "2026-07-22T04:00:00Z"),
        )

        assertThrows(ArithmeticException::class.java) {
            StatisticsEngine.summarize(rows, dayRange(), Direction.EXPENSE)
        }
    }

    @Test
    fun `grand total throws instead of wrapping on overflow`() {
        val rows = listOf(
            tx(1, Long.MAX_VALUE, Direction.EXPENSE, "2026-07-22T04:00:00Z", category = "A"),
            tx(2, 1, Direction.EXPENSE, "2026-07-22T04:00:00Z", category = "B"),
        )

        assertThrows(ArithmeticException::class.java) {
            StatisticsEngine.summarize(rows, dayRange(), Direction.EXPENSE)
        }
    }

    @Test
    fun `equal category totals sort by category name independent of row order`() {
        val rows = listOf(
            tx(1, 100, Direction.EXPENSE, "2026-07-22T04:00:00Z", category = "Food"),
            tx(2, 100, Direction.EXPENSE, "2026-07-22T04:00:00Z", category = "Bills"),
        )

        val categories = StatisticsEngine.summarize(rows, dayRange(), Direction.EXPENSE).categories

        assertEquals(listOf("Bills", "Food"), categories.map { it.category })
    }

    @Test
    fun `transaction rejects blank category`() {
        assertThrows(IllegalArgumentException::class.java) {
            tx(1, 100, Direction.EXPENSE, "2026-07-22T04:00:00Z", category = "   ")
        }
    }

    @Test
    fun `transaction rejects currency outside three uppercase letters`() {
        assertThrows(IllegalArgumentException::class.java) {
            tx(1, 100, Direction.EXPENSE, "2026-07-22T04:00:00Z", currency = "usd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tx(2, 100, Direction.EXPENSE, "2026-07-22T04:00:00Z", currency = "US1")
        }
    }

    private fun dayRange() = DateRange(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22), zone)
}
