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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.CategoryTotal
import com.local.autobookkeeping.ui.home.PENDING_BANNER_TAG
import com.local.autobookkeeping.ui.home.PendingBanner
import com.local.autobookkeeping.ui.statistics.PieChart
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
 * 四套皮肤的「待分类横幅 + 统计饼图」验收：横幅要各有一套材质语言，
 * 饼图要跟着皮肤换色板和切片处理，不能四套长得一样。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SkinBannerChartRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val categories = listOf(
        CategoryTotal("餐饮", 6279),
        CategoryTotal("日用", 1230),
        CategoryTotal("交通", 860),
        CategoryTotal("娱乐", 540),
        CategoryTotal("其他", 320),
    )

    @Test
    fun clearMint() = render(ThemeStyle.CLEAR_MINT)

    @Test
    fun receiptLedger() = render(ThemeStyle.RECEIPT_LEDGER)

    @Test
    fun paperMinimal() = render(ThemeStyle.PAPER_MINIMAL)

    @Test
    fun fattyFish() = render(ThemeStyle.FATTY_FISH)

    /** 窄屏 + 系统大字号：小票的 `NO.019` 和肥鱼的星星最容易把标题挤掉。 */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun receiptsOnNarrowLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        render(ThemeStyle.RECEIPT_LEDGER, "banner-chart-receipt-narrow")
    }

    @Test
    fun bannerClickInvokesCallback() {
        var clicks = 0
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.CLEAR_MINT) {
                Column(Modifier.padding(16.dp)) {
                    PendingBanner(count = 3, onClick = { clicks += 1 })
                }
            }
        }
        compose.onNodeWithTag(PENDING_BANNER_TAG).performClick()
        assertEquals(1, clicks)
    }

    private fun render(style: ThemeStyle, name: String? = null) {
        compose.setContent {
            AutoBookkeepingTheme(style) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    PendingBanner(count = 19, onClick = {})
                    Spacer(Modifier.height(14.dp))
                    AppCardSurface {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PieChart(categories, Modifier.size(190.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(PENDING_BANNER_TAG).assertExists()
        compose.onNodeWithText("19 笔自动记录待分类").assertExists()
        compose.onNodeWithTag("statistics-pie").assertExists()
        save(name ?: "banner-chart-${style.name.lowercase()}")
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
