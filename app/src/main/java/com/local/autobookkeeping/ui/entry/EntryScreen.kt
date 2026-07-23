package com.local.autobookkeeping.ui.entry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val defaultCategories = listOf(
    "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果",
    "服饰", "娱乐", "美容", "通讯", "医疗", "学习", "游戏", "红包",
    "住房", "礼品", "宠物", "汽车", "数码", "办公", "运动", "礼金",
    "捐赠", "彩票", "转账", "其它",
)

@Composable
fun EntryScreen(
    state: EntryUiState,
    onUpdate: ((EntryDraft) -> EntryDraft) -> Unit,
    onSave: (Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val context = LocalContext.current
    val zone = BOOKKEEPING_ZONE
    val dateTime = draft.occurredAt.atZone(zone)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("entry-back"),
                ) { Text("‹ 返回") }
                Text(
                    if (draft.id == null) "记一笔" else "编辑记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                TextButton(onClick = {}, enabled = false) { Text("　　") }
            }
        }
        if (draft.pendingConfirmation) {
            item {
                Text(
                    "这是一笔自动识别记录，请核对方向、分类和商户后再确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A5B00),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionChip(
                    "支出",
                    selected = draft.direction == Direction.EXPENSE,
                ) { onUpdate { it.copy(direction = Direction.EXPENSE) } }
                DirectionChip(
                    "收入",
                    selected = draft.direction == Direction.INCOME,
                ) { onUpdate { it.copy(direction = Direction.INCOME) } }
            }
        }
        item {
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = { value -> onUpdate { it.copy(amountText = value) } },
                label = { Text("金额（元）") },
                leadingIcon = { Text("¥") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount"),
            )
        }
        item {
            Text("分类", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(defaultCategories) { category ->
                    FilterChip(
                        selected = draft.category == category,
                        onClick = { onUpdate { it.copy(category = category) } },
                        label = { Text(category, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = draft.merchant,
                onValueChange = { value -> onUpdate { it.copy(merchant = value) } },
                label = { Text("商户") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("merchant"),
            )
        }
        item {
            OutlinedTextField(
                value = draft.account,
                onValueChange = { value -> onUpdate { it.copy(account = value) } },
                label = { Text("账户") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val changed = replaceLocalDate(
                                    draft.occurredAt,
                                    LocalDate.of(year, month + 1, day),
                                )
                                onUpdate { it.copy(occurredAt = changed) }
                            },
                            dateTime.year,
                            dateTime.monthValue - 1,
                            dateTime.dayOfMonth,
                        ).show()
                    },
                    label = { Text(dateTime.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))) },
                )
                AssistChip(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onUpdate {
                                    it.copy(
                                        occurredAt = dateTime
                                            .withHour(hour)
                                            .withMinute(minute)
                                            .withSecond(0)
                                            .toInstant(),
                                    )
                                }
                            },
                            dateTime.hour,
                            dateTime.minute,
                            true,
                        ).show()
                    },
                    label = { Text(dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))) },
                )
            }
        }
        item {
            OutlinedTextField(
                value = draft.note,
                onValueChange = { value -> onUpdate { it.copy(note = value) } },
                label = { Text("备注") },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("内部转账", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "保存记录，但不计入收支统计",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                Switch(
                    checked = draft.internalTransfer,
                    onCheckedChange = { checked -> onUpdate { it.copy(internalTransfer = checked) } },
                )
            }
        }
        if (state.error != null) {
            item {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("entry-error"),
                )
            }
        }
        item {
            Button(
                onClick = { onSave(false) },
                enabled = !state.saving && !state.deleting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    when {
                        state.saving -> "正在保存…"
                        draft.id == null -> "保存记录"
                        else -> "保存修改"
                    },
                )
            }
        }
        if (draft.pendingConfirmation) {
            item {
                OutlinedButton(
                    onClick = { onSave(true) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("确认并保存")
                }
            }
        }
        if (draft.id != null) {
            item {
                TextButton(
                    onClick = onRequestDelete,
                    enabled = !state.deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.deleting) "正在删除…" else "删除记录")
                }
            }
        }
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除这笔记录？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
