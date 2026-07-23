package com.local.autobookkeeping.ui.ledger

import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerStateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val rows = listOf(
        tx(1, Direction.EXPENSE, "餐饮", "支付宝", "面馆", "午餐", "2026-07-22T04:00:00Z"),
        tx(2, Direction.INCOME, "红包", "微信", "小林", "生日", "2026-07-21T04:00:00Z"),
        tx(3, Direction.EXPENSE, "交通", "银行卡", "地铁", "", "2026-06-21T04:00:00Z"),
    )

    @Test
    fun `filters by month direction category and account`() {
        val state = LedgerFilterState(
            month = YearMonth.of(2026, 7),
            direction = Direction.EXPENSE,
            category = "餐饮",
            account = "支付宝",
        )

        assertEquals(listOf(1L), filterTransactions(rows, state, zone).map { it.id })
    }

    @Test
    fun `search only uses safe displayed transaction fields`() {
        assertEquals(listOf(1L), filterTransactions(rows, LedgerFilterState(query = "面馆"), zone).map { it.id })
        assertEquals(listOf(2L), filterTransactions(rows, LedgerFilterState(query = "生日"), zone).map { it.id })
        assertEquals(emptyList<Long>(), filterTransactions(rows, LedgerFilterState(query = "2026"), zone).map { it.id })
    }

    @Test
    fun `home totals exclude pending and internal rows`() {
        val today = rows + listOf(
            tx(4, Direction.EXPENSE, "转账", "微信", "", "", "2026-07-22T05:00:00Z", internal = true),
            tx(5, Direction.INCOME, "红包", "微信", "", "", "2026-07-22T06:00:00Z", pending = true),
        )

        val summary = calculateHomeSummary(today, Instant.parse("2026-07-22T08:00:00Z"), zone)

        assertEquals(1_000L, summary.expenseMinor)
        assertEquals(0L, summary.incomeMinor)
        assertEquals(1, summary.pendingCount)
        assertFalse(summary.recent.any { it.internalTransfer || it.pendingConfirmation })
    }

    @Test
    fun `ledger grouping is newest first with stable transaction ids`() {
        val groups = groupTransactionsByDate(rows, zone)

        assertEquals(listOf("2026-07-22", "2026-07-21", "2026-06-21"), groups.map { it.date.toString() })
        assertEquals(listOf(1L, 2L, 3L), groups.flatMap { it.transactions }.map { it.id })
        assertTrue(groups.flatMap { it.transactions }.all { it.id > 0 })
    }

    @Test
    fun `default bookkeeping date remains Shanghai when device zone differs`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val groups = groupTransactionsByDate(
                listOf(tx(7, Direction.EXPENSE, "Food", "", "", "", "2026-07-21T16:30:00Z")),
            )

            assertEquals("2026-07-22", groups.single().date.toString())
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `home summary includes confirmed daily balance`() {
        val summary = calculateHomeSummary(
            listOf(
                tx(8, Direction.INCOME, "Salary", "", "", "", "2026-07-22T03:00:00Z")
                    .copy(amountMinor = 2_500),
                tx(9, Direction.EXPENSE, "Food", "", "", "", "2026-07-22T04:00:00Z"),
            ),
            Instant.parse("2026-07-22T08:00:00Z"),
        )

        assertEquals(1_500L, summary.balanceMinor)
    }

    @Test
    fun `search matches formatted amount queries without floating point`() {
        val amountRows = listOf(
            tx(10, Direction.EXPENSE, "Food", "", "", "", "2026-07-22T04:00:00Z")
                .copy(amountMinor = 2_800),
        )

        listOf("28", "28.00", "¥28.00").forEach { query ->
            assertEquals(
                listOf(10L),
                filterTransactions(amountRows, LedgerFilterState(query = query), zone).map { it.id },
                query,
            )
        }
    }

    private fun tx(
        id: Long,
        direction: Direction,
        category: String,
        account: String,
        merchant: String,
        note: String,
        instant: String,
        internal: Boolean = false,
        pending: Boolean = false,
    ) = Transaction(
        id = id,
        amountMinor = 1_000,
        direction = direction,
        category = category,
        account = account,
        merchant = merchant,
        note = note,
        occurredAt = Instant.parse(instant),
        internalTransfer = internal,
        pendingConfirmation = pending,
    )
}
