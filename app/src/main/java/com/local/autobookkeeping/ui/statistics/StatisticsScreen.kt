package com.local.autobookkeeping.ui.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.CategoryTotal
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.ui.theme.AppCardSurface
import com.local.autobookkeeping.ui.theme.HeaderPage
import com.local.autobookkeeping.ui.theme.ThemedHomeHeader
import com.local.autobookkeeping.ui.theme.skinChartPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

const val STATISTICS_LIST_TAG = "statistics-list"
const val STATISTICS_TOTAL_TAG = "statistics-total"

fun statisticsCategoryRowTag(category: String): String =
    "statistics-category-$category"

fun statisticsRangeModeTag(mode: RangeMode): String =
    "statistics-range-${mode.name.lowercase()}"

fun statisticsDirectionTag(direction: Direction): String =
    "statistics-direction-${direction.name.lowercase()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    onModeSelected: (RangeMode) -> Unit = {},
    onDirectionSelected: (Direction) -> Unit = {},
    onMoveRange: (Int) -> Unit = {},
    onDateSelected: (LocalDate) -> Unit = {},
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onCategoryClick: (CategoryTotal) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag(STATISTICS_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ThemedHomeHeader(
                modifier = Modifier.padding(top = 14.dp),
                title = "统计",
                subtitle = "钱都花在哪儿了",
                page = HeaderPage.STATISTICS,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(RangeMode.entries) { mode ->
                    AssistChip(
                        onClick = {
                            onModeSelected(mode)
                            if (mode == RangeMode.CUSTOM) pickerOpen = true
                        },
                        modifier = Modifier
                            .testTag(statisticsRangeModeTag(mode))
                            .semantics { selected = state.rangeMode == mode },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (state.rangeMode == mode) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RangeArrow("‹", "上一时间段") { onMoveRange(-1) }
                Text(
                    text = formatRange(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { pickerOpen = true }
                        .semantics { contentDescription = "选择日期范围" }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
                RangeArrow("›", "下一时间段") { onMoveRange(1) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionChip(Direction.EXPENSE, state.direction == Direction.EXPENSE) {
                    onDirectionSelected(Direction.EXPENSE)
                }
                DirectionChip(Direction.INCOME, state.direction == Direction.INCOME) {
                    onDirectionSelected(Direction.INCOME)
                }
            }
        }
        state.errorMessage?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("statistics-error"),
                )
            }
        }
        item {
            AppCardSurface(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (state.direction == Direction.EXPENSE) "支出合计" else "收入合计",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                    )
                    Text(
                        "¥${formatUnsignedMoney(state.summary.totalMinor)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag(STATISTICS_TOTAL_TAG),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.summary.totalMinor > 0L && state.summary.categories.isNotEmpty()) {
                        PieChart(state.summary.categories, Modifier.size(190.dp))
                    } else {
                        Text(
                            if (state.direction == Direction.EXPENSE) "暂无支出记录" else "暂无收入记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 56.dp),
                        )
                    }
                }
            }
        }
        if (state.summary.categories.isNotEmpty()) {
            item {
                Text("分类排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            itemsIndexed(state.summary.categories, key = { _, item -> item.category }) { index, category ->
                CategoryRow(
                    item = category,
                    index = index,
                    totalMinor = state.summary.totalMinor,
                    onClick = { onCategoryClick(category) },
                )
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    if (pickerOpen) {
        if (state.rangeMode == RangeMode.CUSTOM) {
            CustomRangeDialog(
                start = state.range.start,
                endInclusive = state.range.endInclusive,
                onDismiss = { pickerOpen = false },
                onConfirm = { start, end ->
                    onCustomRangeSelected(start, end)
                    pickerOpen = false
                },
            )
        } else {
            SingleDateDialog(
                initialDate = state.range.start,
                onDismiss = { pickerOpen = false },
                onConfirm = {
                    onDateSelected(it)
                    pickerOpen = false
                },
            )
        }
    }
}

@Composable
private fun RangeArrow(text: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DirectionChip(direction: Direction, selected: Boolean, onClick: () -> Unit) {
    val label = if (direction == Direction.EXPENSE) "支出" else "收入"
    AssistChip(
        onClick = onClick,
        modifier = Modifier
            .testTag(statisticsDirectionTag(direction))
            .semantics { this.selected = selected },
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ),
    )
}

@Composable
private fun CategoryRow(
    item: CategoryTotal,
    index: Int,
    totalMinor: Long,
    onClick: () -> Unit,
) {
    val palette = skinChartPalette
    val percentage = item.amountMinor.toDouble() / totalMinor.toDouble() * 100.0
    AppCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(statisticsCategoryRowTag(item.category))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartLegendSwatch(mark = palette.legendMark, color = palette.colorAt(index))
            Text(
                item.category,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 9.dp)
                    .weight(1f),
            )
            Text(
                String.format(Locale.CHINA, "%.1f%%", percentage),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                "¥${formatUnsignedMoney(item.amountMinor)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDateDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = localDateToUtcPickerMillis(initialDate),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    picker.selectedDateMillis?.let { onConfirm(utcPickerMillisToLocalDate(it)) }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = picker)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    start: LocalDate,
    endInclusive: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = localDateToUtcPickerMillis(start),
        initialSelectedEndDateMillis = localDateToUtcPickerMillis(endInclusive),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    utcPickerRangeOrNull(
                        picker.selectedStartDateMillis,
                        picker.selectedEndDateMillis,
                    )?.let { (selectedStart, selectedEnd) ->
                        onConfirm(selectedStart, selectedEnd)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DateRangePicker(state = picker, modifier = Modifier.height(500.dp))
    }
}

private val RangeMode.label: String
    get() = when (this) {
        RangeMode.DAY -> "日"
        RangeMode.WEEK -> "周"
        RangeMode.MONTH -> "月"
        RangeMode.YEAR -> "年"
        RangeMode.CUSTOM -> "自选"
    }

private fun formatRange(state: StatisticsUiState): String {
    val start = state.range.start
    val end = state.range.endInclusive
    return when (state.rangeMode) {
        RangeMode.DAY -> start.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        RangeMode.WEEK, RangeMode.CUSTOM ->
            "${start.format(DateTimeFormatter.ofPattern("M月d日"))} - " +
                end.format(DateTimeFormatter.ofPattern("M月d日"))
        RangeMode.MONTH -> start.format(DateTimeFormatter.ofPattern("yyyy年M月"))
        RangeMode.YEAR -> "${start.year}年"
    }
}
