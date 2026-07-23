package com.local.autobookkeeping.ui.statistics

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), zone)

    @BeforeEach
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `all five modes create inclusive natural ranges`() = runTest(dispatcher) {
        val viewModel = StatisticsViewModel(FakeLedgerRepository(), clock, zone)
        advanceUntilIdle()

        assertRange(viewModel, RangeMode.DAY, "2026-07-22", "2026-07-22")
        assertRange(viewModel, RangeMode.WEEK, "2026-07-20", "2026-07-26")
        assertRange(viewModel, RangeMode.MONTH, "2026-07-01", "2026-07-31")
        assertRange(viewModel, RangeMode.YEAR, "2026-01-01", "2026-12-31")
        assertTrue(viewModel.selectCustom(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-22")))
        assertEquals(RangeMode.CUSTOM, viewModel.state.value.rangeMode)
        assertEquals(LocalDate.parse("2026-07-01"), viewModel.state.value.range.start)
        assertEquals(LocalDate.parse("2026-07-22"), viewModel.state.value.range.endInclusive)
    }

    @Test
    fun `week is Monday through Sunday at boundary`() = runTest(dispatcher) {
        val mondayClock = Clock.fixed(Instant.parse("2026-07-19T16:00:00Z"), zone)
        val mondayViewModel = StatisticsViewModel(FakeLedgerRepository(), mondayClock, zone)
        mondayViewModel.selectMode(RangeMode.WEEK)
        val sundayClock = Clock.fixed(Instant.parse("2026-07-26T04:00:00Z"), zone)
        val sundayViewModel = StatisticsViewModel(FakeLedgerRepository(), sundayClock, zone)
        sundayViewModel.selectMode(RangeMode.WEEK)
        advanceUntilIdle()

        listOf(mondayViewModel, sundayViewModel).forEach { viewModel ->
            assertEquals(LocalDate.parse("2026-07-20"), viewModel.state.value.range.start)
            assertEquals(LocalDate.parse("2026-07-26"), viewModel.state.value.range.endInclusive)
        }
    }

    @Test
    fun `month and year arrows handle leap days and month ends`() = runTest(dispatcher) {
        val leapClock = Clock.fixed(Instant.parse("2024-02-28T16:00:00Z"), zone)
        val viewModel = StatisticsViewModel(FakeLedgerRepository(), leapClock, zone)

        viewModel.selectMode(RangeMode.MONTH)
        viewModel.moveRange(1)
        assertEquals(LocalDate.parse("2024-03-01"), viewModel.state.value.range.start)
        assertEquals(LocalDate.parse("2024-03-31"), viewModel.state.value.range.endInclusive)
        viewModel.moveRange(-1)
        assertEquals(LocalDate.parse("2024-02-29"), viewModel.state.value.range.endInclusive)

        viewModel.selectMode(RangeMode.YEAR)
        viewModel.moveRange(-1)
        assertEquals(LocalDate.parse("2023-01-01"), viewModel.state.value.range.start)
        assertEquals(LocalDate.parse("2023-12-31"), viewModel.state.value.range.endInclusive)
    }

    @Test
    fun `custom arrows preserve inclusive span and invalid input is ignored`() = runTest(dispatcher) {
        val viewModel = StatisticsViewModel(FakeLedgerRepository(), clock, zone)
        assertTrue(viewModel.selectCustom(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-03")))

        viewModel.moveRange(1)
        assertEquals(LocalDate.parse("2026-07-04"), viewModel.state.value.range.start)
        assertEquals(LocalDate.parse("2026-07-06"), viewModel.state.value.range.endInclusive)
        assertFalse(viewModel.selectCustom(LocalDate.parse("2026-08-02"), LocalDate.parse("2026-08-01")))
        assertEquals(LocalDate.parse("2026-07-04"), viewModel.state.value.range.start)
    }

    @Test
    fun `direction and repository updates recompute summary and exclusions remain applied`() = runTest(dispatcher) {
        val repository = FakeLedgerRepository(
            listOf(
                tx(1, 1_000, Direction.EXPENSE, "餐饮", "2026-07-22"),
                tx(2, 30_000, Direction.INCOME, "红包", "2026-07-22"),
                tx(3, 9_000, Direction.EXPENSE, "转账", "2026-07-22", internal = true),
                tx(4, 8_000, Direction.EXPENSE, "购物", "2026-07-22", pending = true),
            ),
        )
        val viewModel = StatisticsViewModel(repository, clock, zone)
        advanceUntilIdle()

        assertEquals(1_000L, viewModel.state.value.summary.totalMinor)
        viewModel.selectDirection(Direction.INCOME)
        assertEquals(30_000L, viewModel.state.value.summary.totalMinor)

        repository.emit(repository.rows.value + tx(5, 2_000, Direction.INCOME, "工资", "2026-07-22"))
        advanceUntilIdle()
        assertEquals(32_000L, viewModel.state.value.summary.totalMinor)
        repository.emit(
            repository.rows.value.map {
                if (it.id == 5L) it.copy(amountMinor = 5_000, category = "奖金") else it
            },
        )
        advanceUntilIdle()
        assertEquals(35_000L, viewModel.state.value.summary.totalMinor)
        assertTrue(viewModel.state.value.summary.categories.any { it.category == "奖金" })
        repository.emit(repository.rows.value.filterNot { it.id == 2L })
        advanceUntilIdle()
        assertEquals(5_000L, viewModel.state.value.summary.totalMinor)
    }

    @Test
    fun `empty summary and very large long pie sweeps stay finite and exactly close`() = runTest(dispatcher) {
        val viewModel = StatisticsViewModel(FakeLedgerRepository(), clock, zone)
        advanceUntilIdle()
        assertEquals(0L, viewModel.state.value.summary.totalMinor)
        assertTrue(viewModel.state.value.summary.categories.isEmpty())

        val sweeps = calculatePieSweeps(listOf(Long.MAX_VALUE - 10, 5, 5))
        assertTrue(sweeps.all { it.isFinite() && it >= 0.0 })
        assertEquals(360.0, sweeps.sum(), 0.0)
    }

    @Test
    fun `UTC picker millis convert to calendar date without device zone drift`() {
        val date = LocalDate.parse("2026-07-22")
        val millis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(date, utcPickerMillisToLocalDate(millis))
        assertEquals(millis, localDateToUtcPickerMillis(date))
    }

    @Test
    fun `restored selection recreates the same chart state`() = runTest(dispatcher) {
        val range = com.local.autobookkeeping.domain.model.DateRange(
            LocalDate.parse("2026-02-01"),
            LocalDate.parse("2026-02-28"),
            zone,
        )
        val viewModel = StatisticsViewModel(
            repository = FakeLedgerRepository(
                listOf(tx(21, 4_200, Direction.INCOME, "工资", "2026-02-10")),
            ),
            clock = clock,
            zone = zone,
            initialSelection = StatisticsSelection(RangeMode.MONTH, range, Direction.INCOME),
        )
        advanceUntilIdle()

        assertEquals(RangeMode.MONTH, viewModel.state.value.rangeMode)
        assertEquals(range, viewModel.state.value.range)
        assertEquals(Direction.INCOME, viewModel.state.value.direction)
        assertEquals(4_200L, viewModel.state.value.summary.totalMinor)
    }

    @Test
    fun `range movement at calendar limits preserves range and exposes compact error`() = runTest(dispatcher) {
        val viewModel = StatisticsViewModel(FakeLedgerRepository(), clock, zone)

        viewModel.selectDate(LocalDate.MAX)
        val day = viewModel.state.value.range
        viewModel.moveRange(1)
        assertEquals(day, viewModel.state.value.range)
        assertEquals("日期范围超出支持范围", viewModel.state.value.errorMessage)

        viewModel.selectDate(LocalDate.of(999_999_999, 12, 31))
        viewModel.selectMode(RangeMode.MONTH)
        val month = viewModel.state.value.range
        viewModel.moveRange(1)
        assertEquals(month, viewModel.state.value.range)

        viewModel.selectMode(RangeMode.YEAR)
        val year = viewModel.state.value.range
        viewModel.moveRange(1)
        assertEquals(year, viewModel.state.value.range)

        assertTrue(viewModel.selectCustom(LocalDate.MIN, LocalDate.MAX))
        val custom = viewModel.state.value.range
        viewModel.moveRange(Int.MAX_VALUE)
        assertEquals(custom, viewModel.state.value.range)
        assertEquals("日期范围超出支持范围", viewModel.state.value.errorMessage)
    }

    @Test
    fun `aggregate overflow reports error and later repository emission recovers`() = runTest(dispatcher) {
        val repository = FakeLedgerRepository(
            listOf(
                tx(31, Long.MAX_VALUE, Direction.EXPENSE, "餐饮", "2026-07-22"),
                tx(32, 1, Direction.EXPENSE, "交通", "2026-07-22"),
            ),
        )
        val viewModel = StatisticsViewModel(repository, clock, zone)
        advanceUntilIdle()

        assertEquals("统计金额过大，请检查记录", viewModel.state.value.errorMessage)
        assertEquals(0L, viewModel.state.value.summary.totalMinor)

        repository.emit(listOf(tx(33, 2_000, Direction.EXPENSE, "餐饮", "2026-07-22")))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.errorMessage)
        assertEquals(2_000L, viewModel.state.value.summary.totalMinor)
    }

    @Test
    fun `huge pie descriptors use geometry sweeps without long total overflow`() {
        val descriptors = pieSliceDescriptors(
            listOf(
                com.local.autobookkeeping.domain.model.CategoryTotal("餐饮", Long.MAX_VALUE),
                com.local.autobookkeeping.domain.model.CategoryTotal("交通", Long.MAX_VALUE),
            ),
        )

        assertEquals(listOf(180.0, 180.0), descriptors.map { it.sweepDegrees })
        assertTrue(descriptors.all { it.percentage.isFinite() })
        assertEquals(100.0, descriptors.sumOf { it.percentage }, 0.0)
        assertEquals("餐饮，92233720368547758.07元，50.0%", descriptors.first().accessibilityText)
    }

    private fun assertRange(
        viewModel: StatisticsViewModel,
        mode: RangeMode,
        start: String,
        end: String,
    ) {
        viewModel.selectMode(mode)
        assertEquals(LocalDate.parse(start), viewModel.state.value.range.start)
        assertEquals(LocalDate.parse(end), viewModel.state.value.range.endInclusive)
    }

    private fun tx(
        id: Long,
        amount: Long,
        direction: Direction,
        category: String,
        date: String,
        internal: Boolean = false,
        pending: Boolean = false,
    ) = Transaction(
        id = id,
        amountMinor = amount,
        direction = direction,
        category = category,
        occurredAt = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant(),
        internalTransfer = internal,
        pendingConfirmation = pending,
    )

    private class FakeLedgerRepository(initial: List<Transaction> = emptyList()) : LedgerRepository {
        val rows = MutableStateFlow(initial)
        fun emit(value: List<Transaction>) {
            rows.value = value
        }

        override fun observeTransactions(): Flow<List<Transaction>> = rows
        override suspend fun save(transaction: Transaction): Long = transaction.id
        override suspend fun delete(id: Long) = Unit
        override suspend fun findByDedupeKey(key: String): Transaction? = null
        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) {
            rows.value = transactions
        }
    }
}
