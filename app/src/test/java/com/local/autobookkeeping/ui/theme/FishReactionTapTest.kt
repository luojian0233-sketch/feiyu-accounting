package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 点一下头卡贴纸：四页各自的反馈都要播得出来、播完回到待机。
 * 截图落 `build/screenshots/`，用 `reaction-home-{idle,a,b,c,restored}.png` 这种名字区分。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FishReactionTapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeStars() = tapAndCapture(HeaderPage.HOME, "home")

    @Test
    fun ledgerCoins() = tapAndCapture(HeaderPage.LEDGER, "ledger")

    @Test
    fun statisticsStamp() = tapAndCapture(HeaderPage.STATISTICS, "statistics")

    @Test
    fun settingsRing() = tapAndCapture(HeaderPage.SETTINGS, "settings")

    /**
     * 待机态不能挂残影。真机上账单页卡片右下角钉着一枚被圆角切掉一半的金币：
     * `(1f - 0.54f) / 0.46f` 在 Float 下是 0.99999996，过不了 `p >= 1f`，
     * 最后一枚金币就永久留在「已落出槽位」的位置上。
     * 这里只看槽位底边往下 20dp 那块——贴纸和字幕都收在槽位里，能出现在那儿的只有金币。
     */
    @Test
    fun idleLedgerKeepsNoStrayCoin() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    ThemedHomeHeader(page = HeaderPage.LEDGER)
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()

        val sticker = compose.onNodeWithTag(REACTION_TEST_TAG)
        sticker.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, REACTION_STATE_IDLE),
        )

        val density = compose.density.density
        val bounds = sticker.getUnclippedBoundsInRoot()
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))

        val top = (bounds.bottom.value * density).toInt()
        val bottom = ((bounds.bottom.value + 20f) * density).toInt()
        val left = ((bounds.left.value - 12f) * density).toInt()
        val right = ((bounds.right.value + 12f) * density).toInt()

        var strays = 0
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in maxOf(left, 0) until minOf(right, bitmap.width)) {
                if (isCoinPixel(bitmap.getPixel(x, y))) strays++
            }
        }
        assertEquals("槽位下方残留 $strays 个金币像素", 0, strays)
    }

    private fun isCoinPixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return near(r, g, b, 255, 214, 96) || near(r, g, b, 222, 165, 44)
    }

    private fun near(r: Int, g: Int, b: Int, tr: Int, tg: Int, tb: Int): Boolean =
        kotlin.math.abs(r - tr) <= 6 && kotlin.math.abs(g - tg) <= 6 && kotlin.math.abs(b - tb) <= 6

    private fun tapAndCapture(page: HeaderPage, name: String) {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    ThemedHomeHeader(page = page)
                }
            }
        }
        compose.waitForIdle()
        // 帧解码在 Dispatchers.IO 上，Robolectric 不会等它；让它跑完再点
        Thread.sleep(600)
        compose.waitForIdle()
        advance(120)
        save("reaction-$name-idle")

        val sticker = compose.onNodeWithTag(REACTION_TEST_TAG)
        sticker.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, REACTION_STATE_IDLE),
        )
        sticker.performClick()
        compose.waitForIdle()
        sticker.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, REACTION_STATE_PLAYING),
        )
        // delay 由 Compose 的测试时钟驱动：光推 Looper 画面不动，必须 mainClock 逐段推进
        listOf(160, 240, 240, 240).forEachIndexed { index, millis ->
            advance(millis.toLong())
            save("reaction-$name-${'a' + index}")
        }
        advance(1_000)
        sticker.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, REACTION_STATE_IDLE),
        )
        save("reaction-$name-restored")
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
