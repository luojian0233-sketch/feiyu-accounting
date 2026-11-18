package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 四套皮肤 × 四个页面的头卡并排验收：文字是否居中、徽章是否落在右侧槽位、
 * 纸感极简的墨线和小票的齿孔虚线会不会再压到字上。
 *
 * 每套皮肤单独一个测试方法：Robolectric 里反复 setContent 会拖垮测试进程。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HeaderSkinsRenderTest {
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

    /** 窄屏 + 系统大字号：副标题最容易被装饰压到的极端情况，纸感极简单独验一张。 */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun paperMinimalOnNarrowLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        render(ThemeStyle.PAPER_MINIMAL, "headers-paper_minimal-narrow")
    }

    private fun render(style: ThemeStyle, name: String? = null) {
        compose.setContent {
            AutoBookkeepingTheme(style) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(14.dp),
                ) {
                    ThemedHomeHeader()
                    Spacer(Modifier.height(10.dp))
                    ThemedHomeHeader(title = "账单", subtitle = "每一笔都算得清", page = HeaderPage.LEDGER)
                    Spacer(Modifier.height(10.dp))
                    ThemedHomeHeader(title = "统计", subtitle = "钱都花在哪儿了", page = HeaderPage.STATISTICS)
                    Spacer(Modifier.height(10.dp))
                    ThemedHomeHeader(title = "设置", subtitle = "按你的习惯来", page = HeaderPage.SETTINGS)
                }
            }
        }
        compose.waitForIdle()
        // 帧解码在 Dispatchers.IO 上，Robolectric 不会等它
        Thread.sleep(600)
        compose.waitForIdle()
        save(name ?: "headers-${style.name.lowercase()}")
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
