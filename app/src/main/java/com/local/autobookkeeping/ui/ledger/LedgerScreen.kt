package com.local.autobookkeeping.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.theme.AppCardSurface
import java.time.format.DateTimeFormatter

@Composable
fun LedgerScreen(
    state: LedgerUiState,
    onFiltersChanged: ((LedgerFilterState) -> LedgerFilterState) -> Unit,
    onEdit: (Transaction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        Text(
            "账单",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = state.filters.query,
            onValueChange = { query -> onFiltersChanged { it.copy(query = query) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ledger-search"),
            label = { Text("搜索分类、商户、备注或账户") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                FilterRow(state, onFiltersChanged)
            }
            if (state.groups.isEmpty()) {
                item {
                    Text(
                        "没有符合条件的记录",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            state.groups.forEach { group ->
                item(key = "date-${group.date}") {
                    Text(
                        group.date.format(DateTimeFormatter.ofPattern("M月d日")),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(group.transactions, key = { "transaction-${it.id}" }) { transaction ->
                    TransactionRow(transaction, onEdit)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    state: LedgerUiState,
    onFiltersChanged: ((LedgerFilterState) -> LedgerFilterState) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        item {
            CyclingFilterChip(
                label = state.filters.month?.let { "${it.year}年${it.monthValue}月" } ?: "全部月份",
                options = state.months,
                active = state.filters.month,
            ) { value -> onFiltersChanged { it.copy(month = value) } }
        }
        state.filters.dateRange?.let { range ->
            item {
                ToggleFilterChip(
                    "${range.start} - ${range.endInclusive}",
                    selected = true,
                ) {
                    onFiltersChanged { it.copy(dateRange = null) }
                }
            }
        }
        item {
            ToggleFilterChip(
                when (state.filters.direction) {
                    Direction.INCOME -> "收入"
                    Direction.EXPENSE -> "支出"
                    null -> "收支"
                },
                selected = state.filters.direction != null,
            ) {
                onFiltersChanged {
                    it.copy(
                        direction = when (it.direction) {
                            null -> Direction.EXPENSE
                            Direction.EXPENSE -> Direction.INCOME
                            Direction.INCOME -> null
                        },
                    )
                }
            }
        }
        item {
            CyclingFilterChip(
                label = state.filters.category ?: "全部分类",
                options = state.categories,
                active = state.filters.category,
            ) { value -> onFiltersChanged { it.copy(category = value) } }
        }
        item {
            CyclingFilterChip(
                label = state.filters.account ?: "全部账户",
                options = state.accounts,
                active = state.filters.account,
            ) { value -> onFiltersChanged { it.copy(account = value) } }
        }
        if (state.filters.pendingOnly) {
            item {
                ToggleFilterChip("仅待核对", selected = true) {
                    onFiltersChanged { it.copy(pendingOnly = false) }
                }
            }
        }
    }
}

@Composable
private fun ToggleFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) Color(0xFFDDF3EA) else Color.Transparent,
        ),
    )
}

@Composable
private fun <T> CyclingFilterChip(
    label: String,
    options: List<T>,
    active: T?,
    onChange: (T?) -> Unit,
) {
    ToggleFilterChip(label, active != null) {
        if (options.isEmpty()) return@ToggleFilterChip
        val index = options.indexOf(active)
        onChange(if (index == options.lastIndex) null else options[index + 1])
    }
}

@Composable
private fun TransactionRow(transaction: Transaction, onEdit: (Transaction) -> Unit) {
    AppCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transaction-${transaction.id}")
            .clickable { onEdit(transaction) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(transaction.merchant.ifBlank { transaction.category }, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(transaction.category, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    if (transaction.pendingConfirmation) Badge("待核对", Color(0xFFFFE6A8))
                    if (transaction.internalTransfer) Badge("内部转账", Color(0xFFDDE8F6))
                }
            }
            Text(
                formatMoney(transaction.amountMinor, transaction.direction),
                color = if (transaction.direction == Direction.INCOME) Color(0xFF14845A) else Color(0xFF2A332F),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(5.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

fun formatMoney(amountMinor: Long, direction: Direction): String {
    val prefix = if (direction == Direction.INCOME) "+" else "-"
    return "$prefix¥${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
}
