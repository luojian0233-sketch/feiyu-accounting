package com.local.autobookkeeping.ui.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.theme.AppCardSurface
import com.local.autobookkeeping.ui.theme.AppSeparator
import com.local.autobookkeeping.ui.theme.appVisualTokens
import java.time.YearMonth
import java.time.format.DateTimeFormatter

const val LEDGER_LIST_TAG = "ledger-list"
const val LEDGER_SELECT_TOGGLE_TAG = "ledger-select-toggle"
const val LEDGER_SELECT_ALL_TAG = "ledger-select-all"
const val LEDGER_DELETE_SELECTED_TAG = "ledger-delete-selected"

@Composable
fun LedgerScreen(
    state: LedgerUiState,
    onFiltersChanged: ((LedgerFilterState) -> LedgerFilterState) -> Unit,
    onEdit: (Transaction) -> Unit,
    onDelete: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDelete by remember { mutableStateOf<List<Long>?>(null) }
    val allIds = remember(state.groups) { state.groups.flatMap { group -> group.transactions.map { it.id } } }

    fun exitSelection() {
        selecting = false
        selectedIds = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "账单",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = {
                    if (selecting) {
                        exitSelection()
                    } else {
                        selecting = true
                        selectedIds = emptySet()
                    }
                },
                modifier = Modifier.testTag(LEDGER_SELECT_TOGGLE_TAG),
            ) {
                Text(if (selecting) "取消" else "批量")
            }
        }
        if (!selecting) {
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
        }
        if (selecting) {
            SelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allIds.isNotEmpty() && selectedIds.size == allIds.size,
                onToggleAll = {
                    selectedIds = if (selectedIds.size == allIds.size) emptySet() else allIds.toSet()
                },
                onDelete = { pendingDelete = selectedIds.toList() },
            )
        }
        val receiptStyle = appVisualTokens.dashedSeparators
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(LEDGER_LIST_TAG),
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
            if (receiptStyle) {
                items(
                    buildReceiptDayModels(state.groups),
                    key = { "receipt-day-${it.date}" },
                ) { day ->
                    ReceiptDayCard(
                        day = day,
                        selecting = selecting,
                        selectedIds = selectedIds,
                        onEdit = onEdit,
                        onToggle = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                    )
                }
            } else {
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
                        LedgerRow(
                            transaction = transaction,
                            selecting = selecting,
                            selected = transaction.id in selectedIds,
                            onEdit = onEdit,
                            onToggle = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除记录") },
            text = { Text("将删除 ${ids.size} 条记录，删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(ids)
                        pendingDelete = null
                        exitSelection()
                    },
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "已选 $selectedCount 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggleAll,
                modifier = Modifier.testTag(LEDGER_SELECT_ALL_TAG),
            ) { Text(if (allSelected) "取消全选" else "全选") }
            TextButton(
                onClick = onDelete,
                enabled = selectedCount > 0,
                modifier = Modifier.testTag(LEDGER_DELETE_SELECTED_TAG),
            ) { Text("删除") }
        }
    }
}

@Composable
private fun FilterRow(
    state: LedgerUiState,
    onFiltersChanged: ((LedgerFilterState) -> LedgerFilterState) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        item {
            FilterDropdown(
                label = state.filters.month?.let { "${it.year}年${it.monthValue}月" } ?: "全部月份",
                active = state.filters.month != null,
            ) { dismiss ->
                FilterOption("全部月份", selected = state.filters.month == null) {
                    onFiltersChanged { it.copy(month = null) }
                    dismiss()
                }
                state.months.forEach { month ->
                    FilterOption(month.formatLabel(), selected = state.filters.month == month) {
                        onFiltersChanged { it.copy(month = month) }
                        dismiss()
                    }
                }
            }
        }
        item {
            FilterDropdown(
                label = when (state.filters.direction) {
                    Direction.INCOME -> "收入"
                    Direction.EXPENSE -> "支出"
                    null -> "收支"
                },
                active = state.filters.direction != null,
            ) { dismiss ->
                listOf(
                    Triple("全部收支", null, null),
                    Triple("只看支出", Direction.EXPENSE, Direction.EXPENSE),
                    Triple("只看收入", Direction.INCOME, Direction.INCOME),
                ).forEach { (text, value, _) ->
                    FilterOption(text, selected = state.filters.direction == value) {
                        onFiltersChanged { it.copy(direction = value) }
                        dismiss()
                    }
                }
            }
        }
        item {
            FilterDropdown(
                label = if (state.filters.pendingOnly) "待核对" else "全部状态",
                active = state.filters.pendingOnly,
            ) { dismiss ->
                FilterOption("全部状态", selected = !state.filters.pendingOnly) {
                    onFiltersChanged { it.copy(pendingOnly = false) }
                    dismiss()
                }
                FilterOption("只看待核对", selected = state.filters.pendingOnly) {
                    onFiltersChanged { it.copy(pendingOnly = true) }
                    dismiss()
                }
            }
        }
        item {
            FilterDropdown(
                label = state.filters.category ?: "全部分类",
                active = state.filters.category != null,
            ) { dismiss ->
                FilterOption("全部分类", selected = state.filters.category == null) {
                    onFiltersChanged { it.copy(category = null) }
                    dismiss()
                }
                state.categories.forEach { category ->
                    FilterOption(category, selected = state.filters.category == category) {
                        onFiltersChanged { it.copy(category = category) }
                        dismiss()
                    }
                }
            }
        }
        item {
            FilterDropdown(
                label = state.filters.account ?: "全部账户",
                active = state.filters.account != null,
            ) { dismiss ->
                FilterOption("全部账户", selected = state.filters.account == null) {
                    onFiltersChanged { it.copy(account = null) }
                    dismiss()
                }
                state.accounts.forEach { account ->
                    FilterOption(account, selected = state.filters.account == account) {
                        onFiltersChanged { it.copy(account = account) }
                        dismiss()
                    }
                }
            }
        }
        state.filters.dateRange?.let { range ->
            item {
                FilterChipSurface(
                    label = "${range.start} - ${range.endInclusive}",
                    active = true,
                    onClick = { onFiltersChanged { it.copy(dateRange = null) } },
                )
            }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    active: Boolean,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChipSurface(label = label, active = active, expandable = true) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun FilterChipSurface(
    label: String,
    active: Boolean,
    expandable: Boolean = false,
    onClick: () -> Unit,
) {
    val border = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        appVisualTokens.separatorColor
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (expandable) {
                DropdownCaret(
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 下拉箭头：手绘 V 形，避免依赖字体里的 ▾ 字形。 */
@Composable
private fun DropdownCaret(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = 9.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.30f)
            lineTo(w * 0.50f, h * 0.72f)
            lineTo(w * 0.92f, h * 0.30f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun FilterOption(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

private fun YearMonth.formatLabel(): String = "${year}年${monthValue}月"

@Composable
private fun LedgerRow(
    transaction: Transaction,
    selecting: Boolean,
    selected: Boolean,
    onEdit: (Transaction) -> Unit,
    onToggle: (Long) -> Unit,
) {
    AppCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transaction-${transaction.id}")
            .clickable {
                if (selecting) onToggle(transaction.id) else onEdit(transaction)
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle(transaction.id) })
            }
            Box(Modifier.width(11.dp))
            TransactionRowContent(transaction, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReceiptDayCard(
    day: ReceiptDayModel,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onEdit: (Transaction) -> Unit,
    onToggle: (Long) -> Unit,
) {
    AppCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("receipt-day-${day.date}"),
    ) {
        Column {
            Text(
                day.date.format(DateTimeFormatter.ofPattern("M月d日")),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            )
            day.transactions.forEachIndexed { index, transaction ->
                if (index > 0 && day.useRowSeparators) {
                    AppSeparator(Modifier.padding(horizontal = 11.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transaction-${transaction.id}")
                        .clickable {
                            if (selecting) onToggle(transaction.id) else onEdit(transaction)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        Checkbox(
                            checked = transaction.id in selectedIds,
                            onCheckedChange = { onToggle(transaction.id) },
                        )
                    }
                    TransactionRowContent(transaction, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TransactionRowContent(transaction: Transaction, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 11.dp, vertical = 10.dp),
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
