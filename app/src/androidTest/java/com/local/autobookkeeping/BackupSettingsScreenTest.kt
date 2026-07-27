package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.local.autobookkeeping.ui.settings.SettingsScreen
import com.local.autobookkeeping.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackupSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backupControlsShowStableStatusAndRequireDestructiveConfirmation() {
        var exportCalls = 0
        var importCalls = 0
        var confirmCalls = 0
        compose.setContent {
            var screenState by remember {
                mutableStateOf(
                    SettingsUiState(
                        backupStatus = "备份文件无效，未替换当前数据",
                        backupStatusIsError = true,
                    ),
                )
            }
            SettingsScreen(
                state = screenState,
                onThemeSelected = {},
                onWechatChanged = {},
                onAlipayChanged = {},
                onBankDraftChanged = {},
                onSaveBankPackages = {},
                onOpenNotificationAccess = {},
                onAddCategory = {},
                onRenameCategory = { _, _ -> },
                onMoveCategory = { _, _ -> },
                onCategoryHiddenChanged = { _, _ -> },
                onAddAccount = {},
                onRenameAccount = { _, _ -> },
                onMoveAccount = { _, _ -> },
                onAccountHiddenChanged = { _, _ -> },
                onRequestClear = {},
                onConfirmFirstClear = {},
                onConfirmFinalClear = {},
                onDismissClear = {},
                onExportBackup = { exportCalls += 1 },
                onImportBackup = {
                    importCalls += 1
                    screenState = screenState.copy(showBackupRestoreConfirmation = true)
                },
                onConfirmBackupRestore = {
                    confirmCalls += 1
                    screenState = screenState.copy(showBackupRestoreConfirmation = false)
                },
                onDismissBackupRestore = {},
            )
        }

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("导出备份"))
        compose.onNodeWithText("导出备份").performClick()
        compose.onNodeWithText("导入备份").performClick()
        compose.onNodeWithText("当前交易、分类、账户、商户规则和主题将被备份文件替换。").assertIsDisplayed()
        assertEquals(0, confirmCalls)
        compose.onNodeWithText("确认替换").performClick()
        compose.onNodeWithText("备份文件无效，未替换当前数据").assertIsDisplayed()

        assertEquals(1, exportCalls)
        assertEquals(1, importCalls)
        assertEquals(1, confirmCalls)
    }
}
