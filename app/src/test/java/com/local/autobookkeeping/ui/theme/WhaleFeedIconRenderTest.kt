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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * 「鲸鱼饲料」图标取自像素表情包「好好吃」，这里做目视验收：
 * 上半排是分类选择器格子的真实尺寸与配色（24dp 图标槽 + 11sp 分类名），
 * 下半排把图标放大到 24/40/72/180dp，检查像素边、透明留白和整体比例。
 * 截图落在 `build/screenshots/whale-feed-icon.png`，没有真机时也能看。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WhaleFeedIconRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stickerStaysLegibleFromCategoryCellToFullSize() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF7FAFF))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // 与分类选择器同参数：24dp 槽位、12dp 圆角、未选中/选中两种底色
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryCell(selected = false)
                        CategoryCell(selected = true)
                        CategoryCell(selected = false, minWidth = 72.dp)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Zoom(24.dp)
                        Zoom(40.dp)
                        Zoom(72.dp)
                        Zoom(180.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        save()
    }

    @androidx.compose.runtime.Composable
    private fun CategoryCell(selected: Boolean, minWidth: Dp = 48.dp) {
        val container = if (selected) Color(0xFFDCE8FB) else Color(0xFFE9EEF6)
        val content = if (selected) Color(0xFF1B3A66) else Color(0xFF44506A)
        Column(
            modifier = Modifier
                .widthIn(min = minWidth)
                .background(container, RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.category_whale_feed),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = "鲸鱼饲料",
                color = content,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Zoom(iconSize: Dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(iconSize + 14.dp)
                    .background(Color(0xFFE9EEF6), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.category_whale_feed),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            }
            Text(
                text = "${iconSize.value.toInt()}dp",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF7A8699),
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
