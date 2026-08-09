package com.local.autobookkeeping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.local.autobookkeeping.ui.settings.AutomaticBookkeepingSettingsScreen
import com.local.autobookkeeping.ui.settings.SettingsScreen
import com.local.autobookkeeping.ui.settings.SettingsUiState
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AutomaticBookkeepingSettingsScreenTest(
    private val theme: ThemeStyle,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoCardPageKeepsRecoveryAndManualControlsAvailableWhenMasterIsOff() {
        var notificationClicks = 0
        var accessibilityClicks = 0
        var tileClicks = 0
        var batteryClicks = 0
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    SettingsUiState(
                        autoBookkeepingEnabled = false,
                        autoSaveHighConfidence = true,
                    ),
                )
            }
            AutoBookkeepingTheme(theme) {
                AutomaticBookkeepingSettingsScreen(
                    state = state,
                    onBack = {},
                    onAutoChanged = { state = state.copy(autoBookkeepingEnabled = it) },
                    onWechatChanged = { state = state.copy(wechatEnabled = it) },
                    onAlipayChanged = { state = state.copy(alipayEnabled = it) },
                    onAutoSaveChanged = { state = state.copy(autoSaveHighConfidence = it) },
                    onOpenNotificationAccess = { notificationClicks += 1 },
                    onOpenAccessibility = { accessibilityClicks += 1 },
                    onAddQuickSettingsTile = { tileClicks += 1 },
                    onOpenBatteryOptimization = { batteryClicks += 1 },
                )
            }
        }

        compose.onNodeWithText("消费完毕自动记").assertIsDisplayed()
        compose.onNodeWithText("一键截屏记账").assertIsDisplayed()
        compose.onNodeWithTag("automatic-master").assertIsEnabled()
        compose.onNodeWithTag("automatic-wechat").assertIsNotEnabled()
        compose.onNodeWithTag("automatic-alipay").assertIsNotEnabled()
        compose.onNodeWithTag("automatic-auto-save").assertIsEnabled()
        compose.onNodeWithTag("automatic-notification-access").assertIsEnabled().performClick()
        compose.onNodeWithTag("automatic-accessibility-access").assertIsEnabled().performClick()
        compose.onNodeWithTag("automatic-add-tile").assertIsEnabled().performClick()
        compose.onNodeWithTag("automatic-battery-settings").assertIsEnabled().performClick()

        compose.onNodeWithTag("automatic-master").performClick()
        compose.onNodeWithTag("automatic-wechat").assertIsEnabled()
        compose.onNodeWithTag("automatic-alipay").assertIsEnabled()
        assertEquals(1, notificationClicks)
        assertEquals(1, accessibilityClicks)
        assertEquals(1, tileClicks)
        assertEquals(1, batteryClicks)
    }

    @Test
    fun settingsEntryAndSystemBackRoundTripThroughSavedSubpageState() {
        compose.setContent {
            AutoBookkeepingTheme(theme) {
                SettingsScreen(
                    state = SettingsUiState(),
                    onThemeSelected = {},
                    onAutoChanged = {},
                    onWechatChanged = {},
                    onAlipayChanged = {},
                    onAutoSaveChanged = {},
                    onBankDraftChanged = {},
                    onSaveBankPackages = {},
                    onOpenNotificationAccess = {},
                    onOpenAccessibility = {},
                    onAddQuickSettingsTile = {},
                    onOpenBatteryOptimization = {},
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

        compose.onNodeWithTag("automatic-bookkeeping-entry").performClick()
        compose.onNodeWithTag("automatic-settings-list").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("settings-list").assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "theme={0}")
        fun themes(): List<Array<Any>> = ThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
