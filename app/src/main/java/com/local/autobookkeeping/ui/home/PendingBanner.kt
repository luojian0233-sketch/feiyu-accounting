package com.local.autobookkeeping.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.ui.theme.HeaderDecor
import com.local.autobookkeeping.ui.theme.appVisualTokens
import kotlin.math.abs
import kotlin.math.pow

const val PENDING_BANNER_TAG = "pending-banner"

/**
 * 首页「N 笔自动记录待核对」提示条。
 *
 * 四套皮肤各用一种材质语言，而不是同一块奶油色换个色值：
 * 薄荷是嫩芽渐层、小票是虚线齿孔的票据、纸感是带墨线和折角的便签、肥鱼是深海渐变加星光。
 */
@Composable
fun PendingBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decor = appVisualTokens.headerDecor
    val shape = when (decor) {
        HeaderDecor.MINT_SPROUT -> RoundedCornerShape(18.dp)
        HeaderDecor.RECEIPT_STUB -> RoundedCornerShape(2.dp)
        HeaderDecor.PAPER_RULE -> RoundedCornerShape(8.dp)
        HeaderDecor.OCEAN_WAVE -> RoundedCornerShape(20.dp)
    }
    val pageBackground = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PENDING_BANNER_TAG)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) { drawBackdrop(decor, pageBackground) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (decor) {
                HeaderDecor.MINT_SPROUT -> {
                    MintSproutBadge()
                    Spacer(Modifier.width(12.dp))
                }

                HeaderDecor.RECEIPT_STUB -> {
                    ReceiptStubGlyph()
                    Spacer(Modifier.width(12.dp))
                }

                HeaderDecor.PAPER_RULE -> {
                    PaperRuleMark()
                    Spacer(Modifier.width(12.dp))
                }

                HeaderDecor.OCEAN_WAVE -> Unit
            }
            Text(
                text = "$count 笔自动记录待核对",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor(decor),
                fontWeight = if (decor == HeaderDecor.RECEIPT_STUB) {
                    FontWeight.Normal
                } else {
                    FontWeight.SemiBold
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (decor == HeaderDecor.OCEAN_WAVE) {
                // 右上角星星占位，别让长分类名顶到星星上
                Spacer(Modifier.width(26.dp))
            }
            if (decor == HeaderDecor.RECEIPT_STUB) {
                Text(
                    text = "NO." + count.toString().padStart(3, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6E7C6F),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = chevronColor(decor),
            )
        }
    }
}

private fun labelColor(decor: HeaderDecor): Color = when (decor) {
    HeaderDecor.MINT_SPROUT -> Color(0xFF0B493A)
    HeaderDecor.RECEIPT_STUB -> Color(0xFF243B32)
    HeaderDecor.PAPER_RULE -> Color(0xFF18372B)
    HeaderDecor.OCEAN_WAVE -> Color.White
}

private fun chevronColor(decor: HeaderDecor): Color = when (decor) {
    HeaderDecor.MINT_SPROUT -> Color(0xFF2D7D62)
    HeaderDecor.RECEIPT_STUB -> Color(0xFF2F3A33)
    HeaderDecor.PAPER_RULE -> Color(0xFF2E5A46)
    HeaderDecor.OCEAN_WAVE -> Color(0xFFFFE0A3)
}

// ------------------------------------------------------------------ 背景

private fun DrawScope.drawBackdrop(decor: HeaderDecor, pageBackground: Color) {
    when (decor) {
        HeaderDecor.MINT_SPROUT -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFEFFBF5), Color(0xFFD2EEE2))))
            // 右上几点露水；不用大块白色高光，否则会读成一个按钮
            drop(Offset(size.width - 30.dp.toPx(), 18.dp.toPx()), 4.dp.toPx(), 0.24f)
            drop(Offset(size.width - 48.dp.toPx(), 40.dp.toPx()), 2.dp.toPx(), 0.18f)
            drop(Offset(size.width - 18.dp.toPx(), 42.dp.toPx()), 2.dp.toPx(), 0.21f)
        }

        HeaderDecor.RECEIPT_STUB -> {
            drawRect(Color(0xFFFFFEFA))
            // 一圈虚线边框，像撕下来的小票边
            drawRoundRect(
                color = Color(0xFFB8B6AE),
                topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                size = Size(
                    (size.width - 1.dp.toPx()).coerceAtLeast(0f),
                    (size.height - 1.dp.toPx()).coerceAtLeast(0f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(7.dp.toPx(), 5.dp.toPx()),
                    ),
                ),
            )
            // 上下齿孔：画在边线上，被裁掉一半才是"孔"
            val step = size.width / 14f
            for (i in 1 until 14) {
                val cx = step * i
                drawCircle(pageBackground, 4.dp.toPx(), Offset(cx, 0f))
                drawCircle(pageBackground, 4.dp.toPx(), Offset(cx, size.height))
            }
        }

        HeaderDecor.PAPER_RULE -> {
            drawRect(Color(0xFFF7F1E3))
            // 右上一道折角
            val fold = 18.dp.toPx()
            val corner = Path().apply {
                moveTo(size.width - fold, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, fold)
                close()
            }
            drawPath(corner, Color(0xFFE4DCC8))
            drawLine(
                color = Color(0xFFB9B29C),
                start = Offset(size.width - fold, 0f),
                end = Offset(size.width, fold),
                strokeWidth = 1.dp.toPx(),
            )
            // 底部点线，像便签上的引导线
            var x = 22.dp.toPx()
            val end = size.width - 30.dp.toPx()
            val dot = Size(3.dp.toPx(), 1.5.dp.toPx())
            while (x < end) {
                drawRect(Color(0xFF9AA89D), Offset(x, size.height - 6.dp.toPx()), dot)
                x += 7.dp.toPx()
            }
        }

        HeaderDecor.OCEAN_WAVE -> {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF2E5FAE), Color(0xFF4E8FD6))))
            // 装饰止于箭头左侧，别跟「›」抢位置
            drop(Offset(size.width - 30.dp.toPx(), 13.dp.toPx()), 6.dp.toPx(), 0.22f)
            drop(Offset(size.width - 56.dp.toPx(), 41.dp.toPx()), 4.dp.toPx(), 0.16f)
            drop(Offset(size.width - 52.dp.toPx(), 12.dp.toPx()), 3.dp.toPx(), 0.2f)
            sparkle(Offset(size.width - 42.dp.toPx(), 22.dp.toPx()), 7.dp.toPx(), Color(0xFFFFE0A3))
            sparkle(Offset(size.width - 66.dp.toPx(), 33.dp.toPx()), 4.dp.toPx(), Color(0xFFFFF0C8))
        }
    }
}

private fun DrawScope.drop(center: Offset, radius: Float, alpha: Float) {
    drawCircle(Color.White.copy(alpha = alpha), radius, center)
}

/** 四角星：尖角 + 内凹腰身，小尺寸下也比十字更像星星。 */
private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    val k = radius * 0.24f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + k, center.y - k)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + k, center.y + k)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - k, center.y + k)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - k, center.y - k)
        close()
    }
    drawPath(path, color)
}

// ------------------------------------------------------------------ 左侧图标

/** 薄荷：圆底嫩芽，带中脉和叶柄。 */
@Composable
private fun MintSproutBadge() {
    Canvas(Modifier.size(38.dp)) {
        drawCircle(Color(0xFFC6E8DA), radius = size.minDimension / 2f)
        drawLeaf(Offset(size.width / 2f, size.height / 2f - 1.dp.toPx()), 22.dp.toPx(), Color(0xFF2D7D62))
    }
}

private fun DrawScope.drawLeaf(center: Offset, height: Float, color: Color) {
    val halfWidth = height * 0.52f / 2f
    val steps = 24
    val path = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val y = center.y + height / 2f - height * t
        val half = halfWidth * (1f - abs(2f * t - 1f)).pow(0.58f)
        if (i == 0) path.moveTo(center.x - half, y) else path.lineTo(center.x - half, y)
    }
    for (i in steps downTo 0) {
        val t = i / steps.toFloat()
        val y = center.y + height / 2f - height * t
        val half = halfWidth * (1f - abs(2f * t - 1f)).pow(0.58f)
        path.lineTo(center.x + half, y)
    }
    path.close()
    drawPath(path, color)
    // 中脉 + 叶柄，小尺寸下才不会被读成菱形
    drawLine(
        color = Color.White.copy(alpha = 0.45f),
        start = Offset(center.x, center.y + height / 2f),
        end = Offset(center.x, center.y - height / 2f),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y + height / 2f),
        end = Offset(center.x, center.y + height / 2f + 3.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}

/** 小票：矩形票身 + 锯齿底边 + 一条品名线。 */
@Composable
private fun ReceiptStubGlyph() {
    Canvas(Modifier.size(width = 20.dp, height = 26.dp)) {
        val ink = Color(0xFF2F3A33)
        val stroke = 2.dp.toPx()
        val bodyBottom = size.height * 0.82f
        drawRect(
            color = ink,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, bodyBottom - stroke),
            style = Stroke(stroke),
        )
        val step = size.width / 4f
        val teeth = Path().apply {
            moveTo(0f, bodyBottom)
            for (i in 0 until 4) {
                lineTo(step * (i + 0.5f), size.height)
                lineTo(step * (i + 1f), bodyBottom)
            }
        }
        drawPath(teeth, ink, style = Stroke(stroke))
        drawLine(
            color = ink,
            start = Offset(size.width * 0.22f, size.height * 0.30f),
            end = Offset(size.width * 0.78f, size.height * 0.30f),
            strokeWidth = stroke,
        )
    }
}

/** 纸感：一枚竖墨线，跟头卡左侧的竖线同源。 */
@Composable
private fun PaperRuleMark() {
    Canvas(Modifier.size(width = 3.dp, height = 26.dp)) {
        drawRect(Color(0xFF2E5A46))
    }
}
