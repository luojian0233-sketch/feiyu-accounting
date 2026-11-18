package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * 点一下徽章：播一遍「按下」（弹缩 → 荡开 → 收尾），播完回到待机循环。
 * 截图落 `build/screenshots/`，真机不在手边时目视验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HeaderBadgeTapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapPlaysOnceThenReturnsToIdle() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.CLEAR_MINT) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    ThemedHomeHeader()
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        idleFor(BADGE_IDLE_FRAME_MILLIS * 2)
        save("badge-idle")

        compose.onNodeWithTag(BADGE_TEST_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BADGE_STATE_IDLE),
        )
        compose.onNodeWithTag(BADGE_TEST_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BADGE_TEST_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BADGE_STATE_TAPPED),
        )
        listOf("badge-tap-a", "badge-tap-b", "badge-tap-c").forEach { name ->
            advance(240)
            save(name)
        }
        // 16 帧共 1370ms，再给足余量让待机循环接回来
        advance(900)
        compose.onNodeWithTag(BADGE_TEST_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BADGE_STATE_IDLE),
        )
        save("badge-restored")
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
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
