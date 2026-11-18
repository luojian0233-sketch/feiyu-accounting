package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.local.autobookkeeping.ui.navigation.AppDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 渲染冒烟：每套底栏都要能出来，选中/未选中语义都要正确，图标两态都要有区别。
 * 顺带把截图落到 build/screenshots，没有真机时也能目视验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BottomBarRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearMintBottomBarShowsSelectedPill() =
        render(ThemeStyle.CLEAR_MINT, AppDestination.LEDGER, "ledger")

    @Test
    fun receiptLedgerBottomBarShowsSelectedFrame() =
        render(ThemeStyle.RECEIPT_LEDGER, AppDestination.LEDGER, "ledger")

    @Test
    fun paperMinimalBottomBarShowsSelectedRule() =
        render(ThemeStyle.PAPER_MINIMAL, AppDestination.LEDGER, "ledger")

    @Test
    fun fattyFishBottomBarShowsStickerIcons() =
        render(ThemeStyle.FATTY_FISH, AppDestination.LEDGER, "ledger")

    @Test
    fun fattyFishSettingsSelectedKeepsStickerLegible() =
        render(ThemeStyle.FATTY_FISH, AppDestination.SETTINGS, "settings")

    @Test
    fun filledHouseAndBarsReadCorrectly() =
        render(ThemeStyle.CLEAR_MINT, AppDestination.STATISTICS, "statistics")

    @Test
    fun filledGearReadsCorrectly() =
        render(ThemeStyle.CLEAR_MINT, AppDestination.SETTINGS, "settings")

    @Test
    fun filledHouseOnHomeReadsCorrectly() =
        render(ThemeStyle.CLEAR_MINT, AppDestination.HOME, "home")

    @Test
    fun receiptSettingsSelectedReadsAsSliders() =
        render(ThemeStyle.RECEIPT_LEDGER, AppDestination.SETTINGS, "settings")

    @Test
    fun paperSettingsSelectedReadsAsSliders() =
        render(ThemeStyle.PAPER_MINIMAL, AppDestination.SETTINGS, "settings")

    private fun render(style: ThemeStyle, current: AppDestination, suffix: String) {
        compose.setContent {
            AutoBookkeepingTheme(style) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Spacer(Modifier.weight(1f))
                    AppBottomBar(current = current, onSelect = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-${current.name}").assertIsDisplayed().assertIsSelected()
        if (current != AppDestination.HOME) {
            compose.onNodeWithTag("nav-HOME").assertIsNotSelected()
        }
        compose.onNodeWithTag("nav-ENTRY").assertIsDisplayed()
        save(style, suffix)
    }

    private fun save(style: ThemeStyle, suffix: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "bottom-bar-${style.name}-$suffix.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
