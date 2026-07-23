package com.local.autobookkeeping.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.StatisticsSummary
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.statistics.StatisticsEngine
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RangeMode {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    CUSTOM,
}

data class StatisticsUiState(
    val rangeMode: RangeMode,
    val range: DateRange,
    val direction: Direction,
    val summary: StatisticsSummary = StatisticsSummary(0, emptyList()),
)

class StatisticsViewModel(
    repository: LedgerRepository,
    private val clock: Clock = Clock.system(BOOKKEEPING_ZONE),
    private val zone: ZoneId = BOOKKEEPING_ZONE,
) : ViewModel() {
    private var rows: List<Transaction> = emptyList()
    private val today: LocalDate = LocalDate.now(clock.withZone(zone))
    private val mutableState = MutableStateFlow(
        StatisticsUiState(
            rangeMode = RangeMode.DAY,
            range = rangeFor(RangeMode.DAY, today, zone),
            direction = Direction.EXPENSE,
        ),
    )
    val state: StateFlow<StatisticsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeTransactions().collect { updated ->
                rows = updated
                recompute()
            }
        }
    }

    fun selectMode(mode: RangeMode) {
        val current = mutableState.value
        val range = if (mode == RangeMode.CUSTOM) {
            current.range
        } else {
            rangeFor(mode, current.range.start, zone)
        }
        update(current.copy(rangeMode = mode, range = range))
    }

    fun selectDirection(direction: Direction) {
        update(mutableState.value.copy(direction = direction))
    }

    fun selectDate(date: LocalDate) {
        val current = mutableState.value
        val mode = current.rangeMode.takeUnless { it == RangeMode.CUSTOM } ?: RangeMode.DAY
        update(current.copy(rangeMode = mode, range = rangeFor(mode, date, zone)))
    }

    fun selectCustom(start: LocalDate, endInclusive: LocalDate): Boolean {
        if (endInclusive.isBefore(start)) return false
        update(
            mutableState.value.copy(
                rangeMode = RangeMode.CUSTOM,
                range = DateRange(start, endInclusive, zone),
            ),
        )
        return true
    }

    fun moveRange(offset: Int) {
        if (offset == 0) return
        val current = mutableState.value
        val shifted = when (current.rangeMode) {
            RangeMode.DAY -> rangeFor(RangeMode.DAY, current.range.start.plusDays(offset.toLong()), zone)
            RangeMode.WEEK -> rangeFor(RangeMode.WEEK, current.range.start.plusWeeks(offset.toLong()), zone)
            RangeMode.MONTH -> rangeFor(RangeMode.MONTH, current.range.start.plusMonths(offset.toLong()), zone)
            RangeMode.YEAR -> rangeFor(RangeMode.YEAR, current.range.start.plusYears(offset.toLong()), zone)
            RangeMode.CUSTOM -> {
                val inclusiveDays = current.range.endInclusive.toEpochDay() - current.range.start.toEpochDay() + 1
                val delta = Math.multiplyExact(inclusiveDays, offset.toLong())
                DateRange(
                    current.range.start.plusDays(delta),
                    current.range.endInclusive.plusDays(delta),
                    zone,
                )
            }
        }
        update(current.copy(range = shifted))
    }

    private fun update(state: StatisticsUiState) {
        mutableState.value = state.copy(
            summary = StatisticsEngine.summarize(rows, state.range, state.direction),
        )
    }

    private fun recompute() {
        update(mutableState.value)
    }
}

internal fun rangeFor(mode: RangeMode, anchor: LocalDate, zone: ZoneId): DateRange = when (mode) {
    RangeMode.DAY -> DateRange(anchor, anchor, zone)
    RangeMode.WEEK -> {
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        DateRange(monday, monday.plusDays(6), zone)
    }
    RangeMode.MONTH -> DateRange(anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()), zone)
    RangeMode.YEAR -> DateRange(
        LocalDate.of(anchor.year, 1, 1),
        LocalDate.of(anchor.year, 12, 31),
        zone,
    )
    RangeMode.CUSTOM -> DateRange(anchor, anchor, zone)
}

fun utcPickerMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

fun localDateToUtcPickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
