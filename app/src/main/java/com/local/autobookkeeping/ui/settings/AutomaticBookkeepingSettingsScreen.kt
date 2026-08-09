package com.local.autobookkeeping.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.ui.theme.AppSectionCard
import com.local.autobookkeeping.ui.theme.AppSeparator

@Composable
fun AutomaticBookkeepingSettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAutoChanged: (Boolean) -> Unit,
    onWechatChanged: (Boolean) -> Unit,
    onAlipayChanged: (Boolean) -> Unit,
    onAutoSaveChanged: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("automatic-settings-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("automatic-back")) {
                    Text("返回", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "自动记账",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item {
            AppSectionCard {
                AutomaticSectionTitle("消费完毕自动记")
                AutomaticToggleRow(
                    label = "自动记账",
                    checked = state.autoBookkeepingEnabled,
                    enabled = !state.busy,
                    tag = "automatic-master",
                    onCheckedChange = onAutoChanged,
                )
                AppSeparator(Modifier.padding(vertical = 6.dp))
                AutomaticToggleRow(
                    label = "微信",
                    checked = state.wechatEnabled,
                    enabled = !state.busy && state.autoBookkeepingEnabled,
                    tag = "automatic-wechat",
                    onCheckedChange = onWechatChanged,
                )
                AutomaticToggleRow(
                    label = "支付宝",
                    checked = state.alipayEnabled,
                    enabled = !state.busy && state.autoBookkeepingEnabled,
                    tag = "automatic-alipay",
                    onCheckedChange = onAlipayChanged,
                )
                AppSeparator(Modifier.padding(vertical = 6.dp))
                AutomaticPermissionRow(
                    label = "通知读取",
                    enabled = state.notificationAccessEnabled,
                    tag = "automatic-notification-access",
                    onClick = onOpenNotificationAccess,
                )
                AutomaticPermissionRow(
                    label = "页面识别",
                    enabled = state.accessibilityAccessEnabled,
                    tag = "automatic-accessibility-access",
                    onClick = onOpenAccessibility,
                )
                OutlinedButton(
                    onClick = onOpenBatteryOptimization,
                    modifier = Modifier.testTag("automatic-battery-settings"),
                ) {
                    Text("查看电池优化设置", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "支持消费、收款、退款、转账和红包",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    state.automaticAccessMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        state.notificationAccessEnabled && state.accessibilityAccessEnabled
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        item {
            AppSectionCard {
                AutomaticSectionTitle("一键截屏记账")
                OutlinedButton(
                    onClick = onAddQuickSettingsTile,
                    modifier = Modifier.testTag("automatic-add-tile"),
                ) {
                    Text("添加截屏记账按钮", style = MaterialTheme.typography.bodyMedium)
                }
                AutomaticToggleRow(
                    label = "高可信结果自动入账",
                    checked = state.autoSaveHighConfidence,
                    enabled = !state.busy,
                    tag = "automatic-auto-save",
                    onCheckedChange = onAutoSaveChanged,
                )
                Text(
                    "不确定时始终让我确认",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("automatic-settings-error"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomaticSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun AutomaticToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun AutomaticPermissionRow(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (enabled) "已开启" else "未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text("前往设置", style = MaterialTheme.typography.bodySmall)
        }
    }
}
