package com.local.autobookkeeping.domain.statistics

import com.local.autobookkeeping.domain.model.CategoryTotal
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.StatisticsSummary
import com.local.autobookkeeping.domain.model.Transaction

object StatisticsEngine {
    fun summarize(
        rows: List<Transaction>,
        range: DateRange,
        direction: Direction,
    ): StatisticsSummary {
        val categories = rows
            .filter { !it.internalTransfer && it.direction == direction && range.contains(it.occurredAt) }
            .groupingBy { it.category }
            .fold(0L) { total, row -> total + row.amountMinor }
            .map { CategoryTotal(it.key, it.value) }
            .sortedByDescending { it.amountMinor }

        return StatisticsSummary(
            totalMinor = categories.sumOf { it.amountMinor },
            categories = categories,
        )
    }
}
