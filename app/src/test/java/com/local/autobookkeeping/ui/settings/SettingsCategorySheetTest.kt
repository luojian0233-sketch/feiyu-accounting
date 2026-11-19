package com.local.autobookkeeping.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.local.autobookkeeping.data.CategorySetting
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三十多个分类铺在设置页要翻好几屏，而改分类是低频操作。
 * 设置页只留一行摘要，清单收进底部抽屉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsCategorySheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun renderCategorySettings(count: Int, hiddenCount: Int = 0) {
        val categories = (1..count).map { index ->
            CategorySetting(
                id = index.toLong(),
                name = "分类$index",
                displayOrder = index,
                hidden = index <= hiddenCount,
            )
        }
        compose.setContent {
            SettingsScreen(
                state = SettingsUiState(categories = categories),
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
            )
        }
    }

    @Test
    fun `settings shows a summary instead of the whole list`() {
        renderCategorySettings(count = 12, hiddenCount = 2)

        compose.onNodeWithText("共 12 个 · 10 个启用").assertIsDisplayed()
        compose.onNodeWithTag(CATEGORY_MANAGER_ENTRY_TAG).assertIsDisplayed()
        // 清单不应该直接铺在设置页里
        compose.onNodeWithText("分类12").assertDoesNotExist()
    }

    @Test
    fun `tapping the summary opens the manager sheet and done closes it`() {
        renderCategorySettings(count = 12)

        compose.onNodeWithTag(CATEGORY_MANAGER_ENTRY_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CATEGORY_SHEET_TAG).assertExists()
        compose.onNodeWithText("分类12").assertExists()

        compose.onNodeWithTag(CATEGORY_SHEET_DONE_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CATEGORY_SHEET_TAG).assertDoesNotExist()
    }
}
