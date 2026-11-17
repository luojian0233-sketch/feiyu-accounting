package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.R

/**
 * 肥鱼皮肤的自绘装饰元素。
 *
 * 全部由 Canvas 绘制：无位图、无文字、无点击与无障碍语义，尺寸与颜色跟随主题，可任意缩放。
 */

private val WhaleDeep = Color(0xFF1B3566)
private val WhaleMid = Color(0xFF2E5FAE)
private val WhaleFoam = Color(0xFFEAF4FF)

/** 首页顶部的深海横幅：渐层底色 + 三层浪 + 气泡 + 一颗金星。 */
@Composable
fun WhaleWaveHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(28.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(WhaleDeep, WhaleMid), startY = 0f, endY = h))

            fun wave(base: Float, amplitude: Float, color: Color) {
                val path = Path().apply {
                    moveTo(0f, base)
                    cubicTo(w * 0.22f, base - amplitude, w * 0.36f, base + amplitude, w * 0.52f, base)
                    cubicTo(w * 0.68f, base - amplitude, w * 0.82f, base + amplitude, w, base)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(path, color)
            }
            wave(h * 0.58f, h * 0.10f, WhaleFoam.copy(alpha = 0.08f))
            wave(h * 0.72f, h * 0.08f, WhaleFoam.copy(alpha = 0.13f))
            wave(h * 0.86f, h * 0.06f, Color.White.copy(alpha = 0.20f))

            drawCircle(Color.White.copy(alpha = 0.14f), h * 0.050f, Offset(w * 0.70f, h * 0.28f))
            drawCircle(Color.White.copy(alpha = 0.10f), h * 0.032f, Offset(w * 0.79f, h * 0.50f))
            drawCircle(WhaleFoam.copy(alpha = 0.09f), h * 0.022f, Offset(w * 0.62f, h * 0.44f))

            sparkle(Offset(w * 0.18f, h * 0.24f), h * 0.080f, WhaleGold.copy(alpha = 0.92f))
            sparkle(Offset(w * 0.30f, h * 0.68f), h * 0.034f, WhaleGold.copy(alpha = 0.55f))
        }
        content()
    }
}

/** 鲸尾标记：两瓣尾鳍 + 中间凹口 + 细尾柄，用作小标题或分隔装饰。 */
@Composable
fun WhaleTailMark(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.42f, h * 1.00f)
            cubicTo(w * 0.40f, h * 0.88f, w * 0.36f, h * 0.80f, w * 0.38f, h * 0.74f)
            cubicTo(w * 0.30f, h * 0.52f, w * 0.10f, h * 0.22f, w * 0.02f, h * 0.04f)
            cubicTo(w * 0.22f, h * 0.10f, w * 0.40f, h * 0.26f, w * 0.50f, h * 0.46f)
            cubicTo(w * 0.60f, h * 0.26f, w * 0.78f, h * 0.10f, w * 0.98f, h * 0.04f)
            cubicTo(w * 0.90f, h * 0.22f, w * 0.70f, h * 0.52f, w * 0.62f, h * 0.74f)
            cubicTo(w * 0.64f, h * 0.80f, w * 0.60f, h * 0.88f, w * 0.58f, h * 1.00f)
            close()
        }
        drawPath(path, color)
    }
}

/** 四角星：取自立绘里的金色闪光。 */
@Composable
fun SparkleMark(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: Color = WhaleGold,
) {
    Canvas(modifier.size(size)) {
        sparkle(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension / 2f, color)
    }
}

/** 头像：立绘里的头部贴在暖沙色圆盘上，作为皮肤的小吉祥物。 */
@Composable
fun WhaleMascot(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(modifier.size(size)) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFFFFF7E6), Color(0xFFFFE2B2))),
                radius = this.size.minDimension / 2f,
            )
        }
        Image(
            painter = painterResource(R.drawable.whale_face),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape),
        )
    }
}

/** 空记录时的提示：一张整身插画 + 一句引导文案。 */
@Composable
fun WhaleEmptyHint(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.whale_mascot),
            contentDescription = null,
            modifier = Modifier.size(132.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal val WhaleGold = Color(0xFFE8A93C)

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    val waist = radius * 0.24f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + waist, center.y - waist)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + waist, center.y + waist)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - waist, center.y + waist)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - waist, center.y - waist)
        close()
    }
    drawPath(path, color)
}
