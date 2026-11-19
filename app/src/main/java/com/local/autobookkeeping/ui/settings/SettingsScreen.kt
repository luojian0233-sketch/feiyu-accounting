package com.local.autobookkeeping.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.ui.theme.AppSectionCard
import com.local.autobookkeeping.ui.theme.HeaderPage
import com.local.autobookkeeping.ui.theme.ThemedHomeHeader
import com.local.autobookkeeping.ui.theme.AppSeparator
import com.local.autobookkeeping.ui.theme.ThemeStyle

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeSelected: (ThemeStyle) -> Unit,
    onAutoChanged: (Boolean) -> Unit = {},
    onWechatChanged: (Boolean) -> Unit,
    onAlipayChanged: (Boolean) -> Unit,
    onAutoSaveChanged: (Boolean) -> Unit = {},
    onBankDraftChanged: (String) -> Unit,
    onSaveBankPackages: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibility: () -> Unit = {},
    onAddQuickSettingsTile: () -> Unit = {},
    onOpenBatteryOptimization: () -> Unit = {},
    onAddCategory: (String) -> Unit,
    onRenameCategory: (Long, String) -> Unit,
    onMoveCategory: (Long, Int) -> Unit,
    onCategoryHiddenChanged: (Long, Boolean) -> Unit,
    onAddAccount: (String) -> Unit,
    onRenameAccount: (Long, String) -> Unit,
    onMoveAccount: (Long, Int) -> Unit,
    onAccountHiddenChanged: (Long, Boolean) -> Unit,
    onRequestClear: () -> Unit,
    onConfirmFirstClear: () -> Unit,
    onConfirmFinalClear: () -> Unit,
    onDismissClear: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onConfirmBackupRestore: () -> Unit,
    onDismissBackupRestore: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    onOpenDownload: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var newCategory by rememberSaveable { mutableStateOf("") }
    var newAccount by rememberSaveable { mutableStateOf("") }
    var categoryToRename by rememberSaveable { mutableStateOf<Long?>(null) }
    var accountToRename by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAutomaticSettings by rememberSaveable { mutableStateOf(false) }
    var showCategorySheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showAutomaticSettings) {
        showAutomaticSettings = false
    }
    if (showAutomaticSettings) {
        AutomaticBookkeepingSettingsScreen(
            state = state,
            onBack = { showAutomaticSettings = false },
            onAutoChanged = onAutoChanged,
            onWechatChanged = onWechatChanged,
            onAlipayChanged = onAlipayChanged,
            onAutoSaveChanged = onAutoSaveChanged,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onOpenAccessibility = onOpenAccessibility,
            onAddQuickSettingsTile = onAddQuickSettingsTile,
            onOpenBatteryOptimization = onOpenBatteryOptimization,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ThemedHomeHeader(
                modifier = Modifier.padding(top = 14.dp),
                title = "设置",
                subtitle = "按你的习惯来",
                page = HeaderPage.SETTINGS,
            )
        }
        item {
            AppSectionCard {
                SectionTitle("外观")
                Text("切换后立即生效，不会离开当前页面。", style = MaterialTheme.typography.bodySmall)
                ThemeStyle.entries.forEach { style ->
                    FilterChip(
                        selected = state.theme == style,
                        onClick = { onThemeSelected(style) },
                        enabled = !state.busy,
                        label = { Text(style.label) },
                        modifier = Modifier.testTag("theme-${style.name}"),
                    )
                }
            }
        }
        item {
            AppSectionCard(
                modifier = Modifier
                    .testTag("automatic-bookkeeping-entry")
                    .clickable { showAutomaticSettings = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("自动记账")
                        Text(
                            state.automaticAccessMessage,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            AppSectionCard {
                SectionTitle("银行通知来源")
                OutlinedTextField(
                    value = state.bankPackageDraft,
                    onValueChange = onBankDraftChanged,
                    label = { Text("银行应用包名白名单（每行一个）") },
                    supportingText = { Text("仅处理完全匹配的包名，例如 com.example.bank") },
                    enabled = !state.busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSaveBankPackages,
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("保存银行白名单") }
                Text(
                    "由自动记账总开关统一控制。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            AppSectionCard(
                modifier = Modifier
                    .testTag(CATEGORY_MANAGER_ENTRY_TAG)
                    .clickable { showCategorySheet = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "分类",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "共 ${state.categories.size} 个 · " +
                                "${state.categories.count { !it.hidden }} 个启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "管理 ›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item {
            AppSectionCard {
                SectionTitle("账户")
                AddRow(
                    value = newAccount,
                    label = "新账户名称",
                    enabled = !state.busy,
                    onValueChanged = { newAccount = it },
                    onAdd = {
                        onAddAccount(newAccount)
                        newAccount = ""
                    },
                )
                state.accounts.forEachIndexed { index, item ->
                    ManagedRow(
                        name = item.name,
                        hidden = item.hidden,
                        enabled = !state.busy,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.accounts.lastIndex,
                        onRename = { accountToRename = item.id },
                        onMoveUp = { onMoveAccount(item.id, -1) },
                        onMoveDown = { onMoveAccount(item.id, 1) },
                        onHiddenChanged = { onAccountHiddenChanged(item.id, it) },
                    )
                }
            }
        }
        item {
            AppSectionCard {
                SectionTitle("隐私")
                Text(
                    "所有账本、分类、账户与偏好仅保存在本机。应用只读取你明确开启的通知来源，并仅保留记账所需的脱敏信息。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            AppSectionCard {
                SectionTitle("本地备份")
                Text(
                    "通过系统文件选择器导出或导入 JSON 文件，不需要存储权限。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onExportBackup, enabled = !state.busy) {
                        Text("导出备份")
                    }
                    OutlinedButton(onClick = onImportBackup, enabled = !state.busy) {
                        Text("导入备份")
                    }
                }
                state.backupStatus?.let { status ->
                    Text(
                        status,
                        color = if (state.backupStatusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("backup-status").padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            AppSectionCard {
                SectionTitle("清空账本")
                Text(
                    "当前共有 ${state.transactionCount} 笔交易。此操作不可恢复；主题、通知来源、分类和账户设置会保留。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onRequestClear,
                    enabled = !state.busy && state.transactionCount > 0,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("清空账本", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.error != null) {
            item {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("settings-error").padding(bottom = 16.dp),
                )
            }
        }
        item {
            AppSectionCard(modifier = Modifier.testTag(VERSION_CARD_TAG).padding(bottom = 16.dp)) {
                SectionTitle("版本")
                Text(
                    "当前版本 ${state.currentVersionName}（${state.currentVersionCode}）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(VERSION_CURRENT_TAG),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val checking = state.updateState == UpdateCheckState.Checking
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = !checking,
                        modifier = Modifier.testTag(CHECK_UPDATE_TAG),
                    ) { Text(if (checking) "检查中…" else "检查更新") }
                    val available = state.updateState as? UpdateCheckState.Available
                    if (available != null) {
                        Button(
                            onClick = { onOpenDownload(available.downloadUrl) },
                            modifier = Modifier.testTag(OPEN_DOWNLOAD_TAG),
                        ) { Text("去下载") }
                    }
                }
                updateStateMessage(state.updateState)?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.updateState) {
                            is UpdateCheckState.Failed -> MaterialTheme.colorScheme.error
                            is UpdateCheckState.Available -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .testTag(UPDATE_STATUS_TAG)
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }

    if (showCategorySheet) {
        CategoryManagerSheet(
            state = state,
            newCategory = newCategory,
            onNewCategoryChanged = { newCategory = it },
            onAddCategory = {
                onAddCategory(newCategory)
                newCategory = ""
            },
            onRequestRename = { categoryToRename = it },
            onMoveCategory = onMoveCategory,
            onCategoryHiddenChanged = onCategoryHiddenChanged,
            onDismiss = { showCategorySheet = false },
        )
    }

    categoryToRename?.let { id ->
        val item = state.categories.firstOrNull { it.id == id }
        if (item != null) {
            RenameDialog(
                title = "重命名分类",
                initialValue = item.name,
                onConfirm = {
                    onRenameCategory(id, it)
                    categoryToRename = null
                },
                onDismiss = { categoryToRename = null },
            )
        }
    }
    accountToRename?.let { id ->
        val item = state.accounts.firstOrNull { it.id == id }
        if (item != null) {
            RenameDialog(
                title = "重命名账户",
                initialValue = item.name,
                onConfirm = {
                    onRenameAccount(id, it)
                    accountToRename = null
                },
                onDismiss = { accountToRename = null },
            )
        }
    }
    if (state.showFirstClearConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text("确认清空账本？") },
            text = { Text("将永久删除当前 ${state.transactionCount} 笔交易，且无法恢复。分类、账户和偏好会保留。") },
            confirmButton = {
                TextButton(onClick = onConfirmFirstClear) { Text("我了解风险，继续") }
            },
            dismissButton = { TextButton(onClick = onDismissClear) { Text("取消") } },
        )
    }
    if (state.showFinalClearConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text("最后确认") },
            text = { Text("再次确认：永久删除 ${state.transactionCount} 笔交易？") },
            confirmButton = {
                TextButton(onClick = onConfirmFinalClear, enabled = !state.busy) {
                    Text("永久清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismissClear) { Text("返回") } },
        )
    }
    if (state.showBackupRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissBackupRestore,
            title = { Text("确认导入备份？") },
            text = {
                Text("当前交易、分类、账户、商户规则和主题将被备份文件替换。")
            },
            confirmButton = {
                TextButton(onClick = onConfirmBackupRestore, enabled = !state.busy) {
                    Text("确认替换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBackupRestore) { Text("取消") }
            },
        )
    }
}

const val CATEGORY_MANAGER_ENTRY_TAG = "settings-category-manager"
const val CATEGORY_SHEET_TAG = "settings-category-sheet"
const val CATEGORY_SHEET_DONE_TAG = "settings-category-sheet-done"
const val VERSION_CARD_TAG = "settings-version-card"
const val VERSION_CURRENT_TAG = "settings-version-current"
const val CHECK_UPDATE_TAG = "settings-check-update"
const val OPEN_DOWNLOAD_TAG = "settings-open-download"
const val UPDATE_STATUS_TAG = "settings-update-status"

private fun updateStateMessage(state: UpdateCheckState): String? = when (state) {
    UpdateCheckState.Idle -> null
    UpdateCheckState.Checking -> "正在检查…"
    UpdateCheckState.UpToDate -> "已是最新版本"
    is UpdateCheckState.Available ->
        "发现新版本 ${state.versionName}，点击「去下载」在浏览器中打开"
    is UpdateCheckState.Failed -> state.message
    UpdateCheckState.Unconfigured -> "尚未配置更新源（appUpdateRepo）"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AddRow(
    value: String,
    label: String,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd, enabled = enabled) { Text("添加") }
    }
}

/**
 * 分类管理收进底部抽屉。
 *
 * 三十多个分类在设置页里铺开要翻好几屏，而改分类是低频操作——平时只需要知道
 * 「有几个、开了几个」，真要动的时候再拉起来。抽屉里高度充裕，排序也顺手。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagerSheet(
    state: SettingsUiState,
    newCategory: String,
    onNewCategoryChanged: (String) -> Unit,
    onAddCategory: () -> Unit,
    onRequestRename: (Long) -> Unit,
    onMoveCategory: (Long, Int) -> Unit,
    onCategoryHiddenChanged: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .testTag(CATEGORY_SHEET_TAG),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("分类", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "共 ${state.categories.size} 个 · " +
                            "${state.categories.count { !it.hidden }} 个启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(CATEGORY_SHEET_DONE_TAG),
                ) { Text("完成") }
            }
            AddRow(
                value = newCategory,
                label = "新分类名称",
                enabled = !state.busy,
                onValueChanged = onNewCategoryChanged,
                onAdd = onAddCategory,
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            ) {
                itemsIndexed(state.categories, key = { _, item -> item.id }) { index, item ->
                    ManagedRow(
                        name = item.name,
                        hidden = item.hidden,
                        enabled = !state.busy,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.categories.lastIndex,
                        onRename = { onRequestRename(item.id) },
                        onMoveUp = { onMoveCategory(item.id, -1) },
                        onMoveDown = { onMoveCategory(item.id, 1) },
                        onHiddenChanged = { onCategoryHiddenChanged(item.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagedRow(
    name: String,
    hidden: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHiddenChanged: (Boolean) -> Unit,
) {
    Column {
        AppSeparator(Modifier.padding(top = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    // 「新记账时可选」原本每个分类都重复一遍，二十几个分类就是二十几行废话。
                    // 开关本身就说明了这件事，只有「已隐藏」这个反常状态值得单独标一下。
                    if (hidden) {
                        Text(
                            "已隐藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            CompactAction("改名", enabled, onRename)
            CompactAction("↑", enabled && canMoveUp, onMoveUp)
            CompactAction("↓", enabled && canMoveDown, onMoveDown)
            Switch(
                checked = !hidden,
                onCheckedChange = { visible -> onHiddenChanged(!visible) },
                enabled = enabled,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** 排序按钮三连占一整行太浪费，压成贴着名字的小字：28dp 高、无最小宽度。 */
@Composable
private fun CompactAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 8.dp),
    )
}

@Composable
private fun RenameDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val ThemeStyle.label: String
    get() = when (this) {
        ThemeStyle.CLEAR_MINT -> "清透薄荷"
        ThemeStyle.RECEIPT_LEDGER -> "小票账本"
        ThemeStyle.PAPER_MINIMAL -> "纸感极简"
        ThemeStyle.FATTY_FISH -> "蓝色肥鱼"
    }
