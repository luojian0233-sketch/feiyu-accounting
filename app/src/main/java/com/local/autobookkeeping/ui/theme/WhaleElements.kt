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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlin.math.sin
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
            sparkle(Offset(w * 0.60f, h * 0.19f), h * 0.034f, WhaleGold.copy(alpha = 0.55f))
        }
        content()
    }
}

/** 小标题前的水波标记：两道波纹，14dp 下仍能看出是浪而非箭头。 */
@Composable
fun WaveMark(
    modifier: Modifier = Modifier,
    width: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Canvas(modifier.size(width = width, height = width * 0.52f)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            val segments = 40
            for (i in 0..segments) {
                val t = i.toFloat() / segments
                val x = w * t
                val y = h * (0.54f - 0.30f * sin(t * 2f * Math.PI.toFloat()))
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = size.width * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
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

/**
 * 静态头像：立绘里的头部贴在暖沙色圆盘上。头卡已改用循环贴纸
 * （[FishHeaderSticker]），这里保留给需要静止头像的位置。
 */
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
            modifier = Modifier.size(148.dp),
        )
        Spacer(Modifier.height(2.dp))
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
