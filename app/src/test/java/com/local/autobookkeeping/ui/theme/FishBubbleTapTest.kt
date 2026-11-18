package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
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
 * 点一下气泡：播「触碰时」（鼓一下 → 碎成水花 → 重新凝聚），贴纸全程钉在中间，
 * 播完回到「默认状态」循环。截图落 `build/screenshots/`，真机不在手边时目视验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FishBubbleTapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapBurstsAndRestores() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
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
        // 帧解码在 Dispatchers.IO 上，Robolectric 不会等它；让它跑完再点
        Thread.sleep(600)
        compose.waitForIdle()
        idleFor(BUBBLE_IDLE_FRAME_MILLIS * 2)
        save("bubble-idle")

        compose.onNodeWithTag(BUBBLE_TEST_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BUBBLE_STATE_IDLE),
        )
        compose.onNodeWithTag(BUBBLE_TEST_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BUBBLE_TEST_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BUBBLE_STATE_BURSTING),
        )
        // delay 由 Compose 的测试时钟驱动：光推 Looper 画面不动，必须 mainClock 逐段推进
        idleFor(200)
        listOf("burst-a", "burst-b", "burst-c", "burst-d", "burst-e").forEach { name ->
            advance(180)
            save(name)
        }
        // 20 帧总共 1740ms，再给足余量让循环接回默认状态
        advance(900)
        save("bubble-restored")
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
