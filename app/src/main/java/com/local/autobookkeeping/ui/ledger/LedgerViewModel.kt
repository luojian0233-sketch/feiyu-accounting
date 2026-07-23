package com.local.autobookkeeping.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LedgerFilterState(
    val query: String = "",
    val month: YearMonth? = null,
    val dateRange: DateRange? = null,
    val direction: Direction? = null,
    val category: String? = null,
    val account: String? = null,
    val pendingOnly: Boolean = false,
)

data class LedgerGroup(
    val date: LocalDate,
    val transactions: List<Transaction>,
)

data class LedgerUiState(
    val groups: List<LedgerGroup> = emptyList(),
    val filters: LedgerFilterState = LedgerFilterState(),
    val categories: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val months: List<YearMonth> = emptyList(),
)

data class HomeSummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val balanceMinor: Long,
    val pendingCount: Int,
    val recent: List<Transaction>,
)

fun filterTransactions(
    transactions: List<Transaction>,
    filters: LedgerFilterState,
    zone: ZoneId = BOOKKEEPING_ZONE,
): List<Transaction> = transactions.filter { transaction ->
    val monthMatches = filters.month == null ||
        YearMonth.from(transaction.occurredAt.atZone(zone)) == filters.month
    val rangeMatches = filters.dateRange?.contains(transaction.occurredAt) != false
    val query = filters.query.trim()
    val queryMatches = query.isEmpty() || listOf(
        transaction.category,
        transaction.account,
        transaction.merchant,
        transaction.note,
    ).any { it.contains(query, ignoreCase = true) } || amountMatches(transaction, query)
    monthMatches && rangeMatches &&
        (filters.direction == null || transaction.direction == filters.direction) &&
        (filters.category.isNullOrBlank() || transaction.category == filters.category) &&
        (filters.account.isNullOrBlank() || transaction.account == filters.account) &&
        (!filters.pendingOnly || transaction.pendingConfirmation) &&
        queryMatches
}.sortedByDescending { it.occurredAt }

fun groupTransactionsByDate(
    transactions: List<Transaction>,
    zone: ZoneId = BOOKKEEPING_ZONE,
): List<LedgerGroup> = transactions
    .sortedByDescending { it.occurredAt }
    .groupBy { it.occurredAt.atZone(zone).toLocalDate() }
    .map { (date, rows) -> LedgerGroup(date, rows) }
    .sortedByDescending { it.date }

fun calculateHomeSummary(
    transactions: List<Transaction>,
    now: Instant,
    zone: ZoneId = BOOKKEEPING_ZONE,
): HomeSummary {
    val today = now.atZone(zone).toLocalDate()
    val confirmedToday = transactions.filter {
        it.occurredAt.atZone(zone).toLocalDate() == today &&
            !it.pendingConfirmation &&
            !it.internalTransfer
    }
    val income = confirmedToday
            .filter { it.direction == Direction.INCOME }
            .fold(0L) { total, row -> Math.addExact(total, row.amountMinor) }
    val expense = confirmedToday
            .filter { it.direction == Direction.EXPENSE }
            .fold(0L) { total, row -> Math.addExact(total, row.amountMinor) }
    return HomeSummary(
        incomeMinor = income,
        expenseMinor = expense,
        balanceMinor = Math.subtractExact(income, expense),
        pendingCount = transactions.count { it.pendingConfirmation },
        recent = transactions
            .asSequence()
            .filterNot { it.pendingConfirmation || it.internalTransfer }
            .sortedByDescending { it.occurredAt }
            .take(5)
            .toList(),
    )
}

fun statisticsDrilldownFilter(
    category: String,
    range: DateRange,
    direction: Direction,
): LedgerFilterState = LedgerFilterState(
    dateRange = range,
    direction = direction,
    category = category,
)

class LedgerViewModel(
    repository: LedgerRepository,
    private val zone: ZoneId = BOOKKEEPING_ZONE,
) : ViewModel() {
    private val filters = MutableStateFlow(LedgerFilterState())

    val state: StateFlow<LedgerUiState> = combine(repository.observeTransactions(), filters) { rows, activeFilters ->
        val filtered = filterTransactions(rows, activeFilters, zone)
        LedgerUiState(
            groups = groupTransactionsByDate(filtered, zone),
            filters = activeFilters,
            categories = rows.map { it.category }.distinct().sorted(),
            accounts = rows.map { it.account }.filter { it.isNotBlank() }.distinct().sorted(),
            months = rows.map { YearMonth.from(it.occurredAt.atZone(zone)) }.distinct().sortedDescending(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    fun updateFilters(transform: (LedgerFilterState) -> LedgerFilterState) {
        filters.value = transform(filters.value)
    }

    fun clearFilters() {
        filters.value = LedgerFilterState()
    }
}

private fun amountMatches(transaction: Transaction, query: String): Boolean {
    if (query.isEmpty()) return false
    val yuan = "${transaction.amountMinor / 100}.${(transaction.amountMinor % 100).toString().padStart(2, '0')}"
    val signed = if (transaction.direction == Direction.INCOME) "+$yuan" else "-$yuan"
    return listOf(yuan, "¥$yuan", signed, signed.replace("-", "-¥").replace("+", "+¥"))
        .any { it.contains(query, ignoreCase = true) }
}
