package com.local.autobookkeeping.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.theme.AppCardSurface
import com.local.autobookkeeping.ui.theme.HeaderAccentMark
import com.local.autobookkeeping.ui.theme.ThemedHomeHeader
import com.local.autobookkeeping.ui.theme.WhaleEmptyHint
import com.local.autobookkeeping.ui.theme.appVisualTokens
import com.local.autobookkeeping.ui.ledger.formatMoney

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenPending: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onEdit: (Transaction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ThemedHomeHeader(Modifier.padding(top = 18.dp))
        }
        item {
            AppCardSurface {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SummaryCell("今日支出", formatMoney(state.summary.expenseMinor, Direction.EXPENSE))
                    SummaryCell("今日收入", formatMoney(state.summary.incomeMinor, Direction.INCOME))
                    SummaryCell(
                        "今日结余",
                        if (state.summary.balanceMinor >= 0) {
                            formatMoney(state.summary.balanceMinor, Direction.INCOME)
                        } else {
                            formatMoney(-state.summary.balanceMinor, Direction.EXPENSE)
                        },
                    )
                }
            }
        }
        if (state.summary.pendingCount > 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPending),
                    color = Color(0xFFFFF3D8),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "${state.summary.pendingCount} 笔自动记录待核对 ›",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (!state.notificationAccessEnabled) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNotificationSettings),
                    color = Color(0xFFF2F4F3),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("自动记账尚未开启", fontWeight = FontWeight.Medium)
                        Text(
                            "前往系统设置授予通知读取权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderAccentMark()
                Spacer(Modifier.width(7.dp))
                Text("最近记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.summary.recent.isEmpty()) {
            item {
                if (appVisualTokens.showFishMascot) {
                    WhaleEmptyHint("还没有记录，点下方“记一笔”开始。")
                } else {
                    Text(
                        "还没有记录，点下方“记一笔”开始。",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 22.dp),
                    )
                }
            }
        } else {
            items(state.summary.recent, key = { it.id }) { transaction ->
                RecentTransactionRow(transaction, onEdit)
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64736E))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentTransactionRow(transaction: Transaction, onEdit: (Transaction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(transaction) }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(transaction.merchant.ifBlank { transaction.category }, style = MaterialTheme.typography.bodyMedium)
            Text(
                transaction.category,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        Text(
            formatMoney(transaction.amountMinor, transaction.direction),
            color = if (transaction.direction == Direction.INCOME) Color(0xFF14845A) else Color(0xFF27312E),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
