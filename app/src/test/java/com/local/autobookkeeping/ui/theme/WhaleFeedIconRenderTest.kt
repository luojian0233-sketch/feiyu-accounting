package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 「鲸鱼饲料」图标取自立绘头像，这里做目视验收：
 * 24dp 的格子（分类选择器的真实尺寸）里要能认出是同一个角色，边缘不能有白边或方角。
 * 截图落在 `build/screenshots/whale-feed-icon.png`，没有真机时也能看。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WhaleFeedIconRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun avatarStaysLegibleFromCategoryCellToFullSize() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF7FAFF))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 与分类选择器同尺寸：未选中 / 选中两种底色
                        Cell(Color(0xFFE9EEF6), 24.dp)
                        Cell(Color(0xFFDCE8FB), 24.dp)
                        Cell(Color(0xFFE9EEF6), 40.dp)
                        Cell(Color(0xFFDCE8FB), 72.dp)
                    }
                    Image(
                        painter = painterResource(R.drawable.category_whale_feed),
                        contentDescription = null,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
        compose.waitForIdle()
        save()
    }

    @androidx.compose.runtime.Composable
    private fun Cell(background: Color, iconSize: Dp) {
        Box(
            modifier = Modifier
                .size(iconSize + 22.dp)
                .background(background, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.category_whale_feed),
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape),
            )
        }
    }

    private fun save() {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "whale-feed-icon.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
