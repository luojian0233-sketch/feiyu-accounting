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
 * 点一下气泡：播「触碰时」（鼓一下 → 碎成水花 → 重新凝聚），期间贴纸跟着淡出，
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

        compose.onNodeWithTag(BUBBLE_TEST_TAG).performClick()
        // 先让后台把 20 帧破损帧读完，再推进到「碎成水花」那几帧
        idleFor(200)
        idleFor(500)
        save("bubble-burst")

        // 再等一整段走完，气泡该自己复原、贴纸回来
        idleFor(1500)
        save("bubble-restored")
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
