package com.local.autobookkeeping.ui.entry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
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
 * 局内键盘四套皮肤的验收：键盘要跟着皮肤换材质，
 * 不能四套长得一样；键位与回调保持完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AmountKeypadRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearMint() = render(ThemeStyle.CLEAR_MINT)

    @Test
    fun receiptLedger() = render(ThemeStyle.RECEIPT_LEDGER)

    @Test
    fun paperMinimal() = render(ThemeStyle.PAPER_MINIMAL)

    @Test
    fun fattyFish() = render(ThemeStyle.FATTY_FISH)

    /** 窄屏 + 系统大字号：验证 4×4 键位不会被字号挤破。 */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun fattyFishOnNarrowLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        render(ThemeStyle.FATTY_FISH, "amount-keypad-fish-narrow")
    }

    @Test
    fun everyKeyReportsItsValue() {
        val pressed = mutableListOf<AmountKey>()
        var done = 0
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                AmountKeypad(
                    visible = true,
                    onKey = { pressed += it },
                    onDone = { done += 1 },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("keypad-7").performClick()
        compose.onNodeWithTag("keypad-0").performClick()
        compose.onNodeWithTag("keypad-dot").performClick()
        compose.onNodeWithTag("keypad-00").performClick()
        compose.onNodeWithTag("keypad-back").performClick()
        compose.onNodeWithTag("keypad-clear").performClick()
        compose.onNodeWithTag(KEYPAD_DONE_TAG).performClick()

        assertEquals(
            listOf(
                AmountKey.Digit(7),
                AmountKey.Digit(0),
                AmountKey.Dot,
                AmountKey.DoubleZero,
                AmountKey.Backspace,
                AmountKey.Clear,
            ),
            pressed,
        )
        assertEquals(1, done)
    }

    @Test
    fun hiddenKeypadRendersNothing() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                AmountKeypad(visible = false, onKey = {}, onDone = {})
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(AMOUNT_KEYPAD_TAG).assertCountEquals(0)
    }

    private fun render(style: ThemeStyle, name: String? = null) {
        compose.setContent {
            AutoBookkeepingTheme(style) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Spacer(Modifier.weight(1f))
                    AmountFieldDisplay(
                        amountText = "128.50",
                        focused = true,
                        onClick = {},
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    AmountKeypad(
                        visible = true,
                        onKey = {},
                        onDone = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AMOUNT_KEYPAD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(KEYPAD_DONE_TAG).assertIsDisplayed()
        compose.onNodeWithText("清空").assertIsDisplayed()
        repeat(10) { digit ->
            compose.onNodeWithTag("keypad-$digit").assertIsDisplayed()
        }
        save(name ?: "amount-keypad-${style.name.lowercase()}")
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
