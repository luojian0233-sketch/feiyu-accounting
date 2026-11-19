package com.local.autobookkeeping.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 版本卡片压在设置页最末尾，是低频但必须能一眼看懂的东西：
 * 现在装的是哪版、有没有新版、新版在哪下。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsVersionCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        state: SettingsUiState,
        onCheck: () -> Unit = {},
        onDownload: (String) -> Unit = {},
    ) {
        compose.setContent {
            SettingsScreen(
                state = state,
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
                onExportBackup = {},
                onImportBackup = {},
                onConfirmBackupRestore = {},
                onDismissBackupRestore = {},
                onCheckUpdate = onCheck,
                onOpenDownload = onDownload,
            )
        }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(VERSION_CARD_TAG))
    }

    @Test
    fun `version card shows the installed version and runs the check`() {
        var checked = false
        render(
            state = SettingsUiState(currentVersionName = "1.0", currentVersionCode = 1),
            onCheck = { checked = true },
        )

        compose.onNodeWithTag(VERSION_CURRENT_TAG).assertTextContains("1.0", substring = true)
        compose.onNodeWithTag(CHECK_UPDATE_TAG).performClick()

        assertTrue(checked)
    }

    @Test
    fun `available release reveals a download action carrying its url`() {
        var opened: String? = null
        render(
            state = SettingsUiState(
                currentVersionName = "1.0",
                currentVersionCode = 1,
                updateState = UpdateCheckState.Available(
                    versionName = "1.2.0",
                    downloadUrl = "https://example.com/app.apk",
                    changelog = "",
                ),
            ),
            onDownload = { opened = it },
        )

        compose.onNodeWithTag(OPEN_DOWNLOAD_TAG).performClick()

        assertEquals("https://example.com/app.apk", opened)
    }

    @Test
    fun `checking disables the button so requests cannot pile up`() {
        render(
            state = SettingsUiState(
                currentVersionName = "1.0",
                currentVersionCode = 1,
                updateState = UpdateCheckState.Checking,
            ),
        )

        compose.onNodeWithTag(CHECK_UPDATE_TAG).assertIsNotEnabled()
    }
}
