package com.local.autobookkeeping.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DateRange(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val zone: ZoneId,
) {
    init {
        require(!endInclusive.isBefore(start))
    }

    fun contains(instant: Instant): Boolean {
        val date = instant.atZone(zone).toLocalDate()
        return !date.isBefore(start) && !date.isAfter(endInclusive)
    }
}

data class CategoryTotal(
    val category: String,
    val amountMinor: Long,
)

data class StatisticsSummary(
    val totalMinor: Long,
    val categories: List<CategoryTotal>,
)
