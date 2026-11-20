package com.local.autobookkeeping.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 版本卡片压在设置页最末尾，是低频但必须能一眼看懂的东西：
 * 现在装的是哪版、有没有新版、新版在哪下。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsVersionCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        state: SettingsUiState,
        onCheck: () -> Unit = {},
        onDownload: (String) -> Unit = {},
        onDownloadAndInstall: (String) -> Unit = {},
        onInstallApk: (String) -> Unit = {},
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
                onDownloadAndInstall = onDownloadAndInstall,
                onOpenDownload = onDownload,
                onInstallApk = onInstallApk,
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

    @Test
    fun `download and install button starts the in app flow with the asset url`() {
        var started: String? = null
        render(
            state = SettingsUiState(
                currentVersionName = "1.2",
                currentVersionCode = 3,
                updateState = UpdateCheckState.Available(
                    versionName = "1.3",
                    downloadUrl = "https://example.com/feiyu-1.3-vc4.apk",
                    changelog = "",
                ),
            ),
            onDownloadAndInstall = { started = it },
        )

        compose.onNodeWithTag(DOWNLOAD_AND_INSTALL_TAG).performClick()

        assertEquals("https://example.com/feiyu-1.3-vc4.apk", started)
    }

    @Test
    fun `downloading shows progress and locks the button`() {
        render(
            state = SettingsUiState(
                currentVersionName = "1.2",
                currentVersionCode = 3,
                updateState = UpdateCheckState.Available(
                    versionName = "1.3",
                    downloadUrl = "https://example.com/feiyu-1.3-vc4.apk",
                    changelog = "",
                ),
                install = UpdateInstallState.Downloading(42),
            ),
        )

        compose.onNodeWithTag(INSTALL_STATUS_TAG).assertTextContains("42%", substring = true)
        compose.onNodeWithTag(DOWNLOAD_AND_INSTALL_TAG).assertIsNotEnabled()
    }

    @Test
    fun `ready install state hands the apk path to the installer exactly once`() {
        val paths = mutableListOf<String>()
        render(
            state = SettingsUiState(
                currentVersionName = "1.2",
                currentVersionCode = 3,
                install = UpdateInstallState.Ready("1.3", 4, "/cache/update/feiyu-download.apk"),
            ),
            onInstallApk = { paths += it },
        )

        compose.waitForIdle()

        assertEquals(listOf("/cache/update/feiyu-download.apk"), paths)
    }

    /**
     * 目视验收：版本卡片现在有「当前版本、检查更新、下载并安装、进度提示」几行，
     * 挤不挤、进度文案够不够清楚，看截图比读断言快。落 build/screenshots/settings-version-card.png。
     */
    @Test
    fun `version card screenshot shows download progress`() {
        render(
            state = SettingsUiState(
                currentVersionName = "1.2",
                currentVersionCode = 3,
                updateState = UpdateCheckState.Available(
                    versionName = "1.3",
                    downloadUrl = "https://example.com/feiyu-1.3-vc4.apk",
                    changelog = "",
                ),
                install = UpdateInstallState.Downloading(42),
            ),
        )

        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        val directory = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "settings-version-card.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
