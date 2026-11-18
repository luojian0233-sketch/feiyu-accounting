package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** 底部导航用的图标，四套皮肤各用一种几何语言。 */
enum class NavGlyphKind { HOME, LEDGER, STATISTICS, SETTINGS }

/**
 * 导航图标。未选中画描边，选中画实心剪影，方便一眼看出当前页。
 * [selected] 目前只对前三套皮肤生效，肥鱼那套等素材到位再补。
 */
@Composable
fun NavGlyph(
    kind: NavGlyphKind,
    modifier: Modifier = Modifier,
    size: Dp = 23.dp,
    selected: Boolean = false,
) {
    val tint = LocalContentColor.current
    val decor = appVisualTokens.headerDecor
    Canvas(modifier.size(size)) {
        when (decor) {
            HeaderDecor.MINT_SPROUT -> drawMint(kind, tint, selected)
            HeaderDecor.RECEIPT_STUB -> drawReceipt(kind, tint, selected)
            HeaderDecor.PAPER_RULE -> drawPaper(kind, tint, selected)
            HeaderDecor.OCEAN_WAVE -> drawOcean(kind, tint)
        }
    }
}

private fun DrawScope.box(v: Float): Float = size.minDimension * v

private fun DrawScope.line(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    color: Color,
    width: Float,
    cap: StrokeCap = StrokeCap.Round,
) {
    drawLine(
        color = color,
        start = Offset(box(x1), box(y1)),
        end = Offset(box(x2), box(y2)),
        strokeWidth = width,
        cap = cap,
    )
}

private fun DrawScope.linePx(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    color: Color,
    width: Float,
    cap: StrokeCap = StrokeCap.Round,
) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = cap)
}

private fun DrawScope.polyline(points: List<Pair<Float, Float>>, color: Color, width: Float, cap: StrokeCap) {
    val path = Path().apply {
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(box(x), box(y)) else lineTo(box(x), box(y))
        }
    }
    drawPath(path, color, style = Stroke(width = width, cap = cap, join = StrokeJoin.Round))
}

private fun DrawScope.cog(
    tint: Color,
    stroke: Float,
    teeth: Int,
) {
    val cx = box(0.50f)
    val cy = box(0.50f)
    repeat(teeth) { i ->
        val angle = i * (2f * Math.PI.toFloat() / teeth) - Math.PI.toFloat() / 2f
        val inner = box(0.205f)
        val outer = box(0.305f)
        linePx(
            cx + inner * cos(angle), cy + inner * sin(angle),
            cx + outer * cos(angle), cy + outer * sin(angle),
            tint, stroke * 1.7f,
        )
    }
    drawCircle(tint, box(0.235f), Offset(cx, cy), style = Stroke(width = stroke * 1.5f))
    drawCircle(tint, box(0.085f), Offset(cx, cy), style = Stroke(width = stroke * 0.9f))
}

private fun DrawScope.wavyLine(
    x1: Float,
    x2: Float,
    y: Float,
    amplitude: Float,
    color: Color,
    width: Float,
    cycles: Float = 1.5f,
) {
    val steps = 36
    val path = Path()
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val x = x1 + (x2 - x1) * t
        val yy = y - amplitude * sin(t * cycles * 2f * Math.PI.toFloat())
        if (i == 0) path.moveTo(box(x), box(yy)) else path.lineTo(box(x), box(yy))
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- 实心剪影
// 选中态统一用「实心形状减去内部细节」的做法：房子挖掉门洞、账单挖掉两行字、
// 齿轮挖掉轴孔。比单纯加粗线条更像真正的 filled 图标。

private fun union(a: Path, b: Path): Path = Path().apply { op(a, b, PathOperation.Union) }

private fun diff(a: Path, b: Path): Path = Path().apply { op(a, b, PathOperation.Difference) }

private fun DrawScope.roundRectPath(l: Float, t: Float, r: Float, b: Float, radius: Float): Path =
    Path().apply {
        addRoundRect(
            RoundRect(box(l), box(t), box(r), box(b), CornerRadius(radius)),
        )
    }

private fun DrawScope.houseSilhouette(tint: Color, radius: Float) {
    val roof = Path().apply {
        moveTo(box(0.50f), box(0.10f))
        lineTo(box(0.95f), box(0.49f))
        lineTo(box(0.05f), box(0.49f))
        close()
    }
    val body = roundRectPath(0.15f, 0.44f, 0.85f, 0.89f, radius)
    val house = union(roof, body)
    val door = roundRectPath(0.415f, 0.615f, 0.585f, 0.89f, radius * 0.75f)
    drawPath(diff(house, door), tint)
}

private fun DrawScope.slipSilhouette(tint: Color, radius: Float) {
    val body = roundRectPath(0.19f, 0.12f, 0.81f, 0.88f, radius)
    val line1 = roundRectPath(0.31f, 0.32f, 0.69f, 0.395f, radius * 0.6f)
    val line2 = roundRectPath(0.31f, 0.55f, 0.57f, 0.625f, radius * 0.6f)
    val withoutLine1 = diff(body, line1)
    drawPath(diff(withoutLine1, line2), tint)
}

private fun DrawScope.barsSilhouette(tint: Color, radius: Float) {
    val tops = listOf(0.56f, 0.42f, 0.28f)
    val barWidth = 0.13f
    tops.forEachIndexed { index, top ->
        val left = 0.235f + index * 0.20f
        drawPath(roundRectPath(left, top, left + barWidth, 0.85f, radius), tint)
    }
}

private fun DrawScope.gearSilhouette(tint: Color) {
    // 逐齿描一圈「梯形齿 + 牙根」的轮廓再减去轴孔：齿是宽的梯形，
    // 不是放射状尖刺，否则在 21dp 下会读成一朵花或太阳。
    val teeth = 8
    val step = (2f * Math.PI / teeth).toFloat()
    val root = box(0.212f)
    val tip = box(0.300f)
    val outline = Path()
    for (i in 0 until teeth) {
        val base = i * step - Math.PI.toFloat() / 2f
        val points = listOf(
            root to base - step * 0.5f,
            tip to base - step * 0.20f,
            tip to base + step * 0.20f,
            root to base + step * 0.5f,
        )
        points.forEachIndexed { index, (r, angle) ->
            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)
            if (i == 0 && index == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
    }
    outline.close()
    val holeRadius = box(0.105f)
    val hole = Path().apply {
        addOval(
            Rect(
                center.x - holeRadius,
                center.y - holeRadius,
                center.x + holeRadius,
                center.y + holeRadius,
            ),
        )
    }
    drawPath(diff(outline, hole), tint)
}

/** 选中态的滑杆：粗杆 + 实心旋钮。 */
private fun DrawScope.slidersFilled(
    tint: Color,
    barWidth: Float,
    knobFraction: Float,
    square: Boolean,
) {
    val knob = box(knobFraction)
    listOf(
        0.36f to 0.37f,
        0.66f to 0.63f,
    ).forEach { (y, knobX) ->
        line(0.13f, y, 0.87f, y, tint, barWidth)
        val corner = if (square) CornerRadius(knob * 0.10f) else CornerRadius(knob / 2f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(box(knobX) - knob / 2f, box(y) - knob / 2f),
            size = Size(knob, knob),
            cornerRadius = corner,
        )
    }
}

/** 清透薄荷：圆头描边 + 一点实心，像叶子一样柔和。 */
private fun DrawScope.drawMint(kind: NavGlyphKind, tint: Color, selected: Boolean) {
    val w = 1.9.dp.toPx()
    if (selected) {
        when (kind) {
            NavGlyphKind.HOME -> houseSilhouette(tint, box(0.075f))
            NavGlyphKind.LEDGER -> slipSilhouette(tint, box(0.10f))
            NavGlyphKind.STATISTICS -> barsSilhouette(tint, box(0.048f))
            NavGlyphKind.SETTINGS -> gearSilhouette(tint)
        }
        return
    }
    when (kind) {
        NavGlyphKind.HOME -> {
            polyline(listOf(0.16f to 0.50f, 0.50f to 0.20f, 0.84f to 0.50f), tint, w, StrokeCap.Round)
            polyline(
                listOf(0.24f to 0.50f, 0.24f to 0.82f, 0.76f to 0.82f, 0.76f to 0.50f),
                tint,
                w,
                StrokeCap.Round,
            )
            drawCircle(tint, box(0.062f), Offset(box(0.50f), box(0.64f)))
        }
        NavGlyphKind.LEDGER -> {
            val radius = box(0.10f)
            drawPath(roundRectPath(0.22f, 0.16f, 0.78f, 0.84f, radius), tint, style = Stroke(width = w))
            line(0.34f, 0.40f, 0.66f, 0.40f, tint, w)
            line(0.34f, 0.60f, 0.58f, 0.60f, tint, w)
        }
        NavGlyphKind.STATISTICS -> {
            val heights = listOf(0.52f, 0.38f, 0.26f)
            heights.forEachIndexed { index, top ->
                val x = 0.30f + index * 0.20f
                line(x, 0.82f, x, top, tint, w * 1.55f)
            }
        }
        NavGlyphKind.SETTINGS -> cog(tint, w, teeth = 8)
    }
}

/** 小票账本：直角、等宽线，像打印出来的小票。 */
private fun DrawScope.drawReceipt(kind: NavGlyphKind, tint: Color, selected: Boolean) {
    val w = 1.6.dp.toPx()
    if (selected) {
        when (kind) {
            NavGlyphKind.HOME -> houseSilhouette(tint, box(0.005f))
            NavGlyphKind.LEDGER -> slipSilhouette(tint, box(0.008f))
            NavGlyphKind.STATISTICS -> barsSilhouette(tint, box(0.008f))
            NavGlyphKind.SETTINGS -> slidersFilled(tint, w * 2.1f, 0.275f, square = true)
        }
        return
    }
    when (kind) {
        NavGlyphKind.HOME -> {
            polyline(
                listOf(0.14f to 0.50f, 0.50f to 0.18f, 0.86f to 0.50f),
                tint,
                w,
                StrokeCap.Butt,
            )
            polyline(
                listOf(0.24f to 0.48f, 0.24f to 0.84f, 0.76f to 0.84f, 0.76f to 0.48f),
                tint,
                w,
                StrokeCap.Butt,
            )
            line(0.42f, 0.84f, 0.42f, 0.64f, tint, w, StrokeCap.Butt)
            line(0.42f, 0.64f, 0.58f, 0.64f, tint, w, StrokeCap.Butt)
            line(0.58f, 0.64f, 0.58f, 0.84f, tint, w, StrokeCap.Butt)
        }
        NavGlyphKind.LEDGER -> {
            val path = Path().apply {
                moveTo(box(0.26f), box(0.16f))
                lineTo(box(0.74f), box(0.16f))
                lineTo(box(0.74f), box(0.82f))
                lineTo(box(0.66f), box(0.74f))
                lineTo(box(0.58f), box(0.82f))
                lineTo(box(0.50f), box(0.74f))
                lineTo(box(0.42f), box(0.82f))
                lineTo(box(0.34f), box(0.74f))
                lineTo(box(0.26f), box(0.82f))
                close()
            }
            drawPath(path, tint, style = Stroke(width = w, join = StrokeJoin.Miter))
            line(0.36f, 0.38f, 0.64f, 0.38f, tint, w * 0.8f, StrokeCap.Butt)
            line(0.36f, 0.54f, 0.56f, 0.54f, tint, w * 0.8f, StrokeCap.Butt)
        }
        NavGlyphKind.STATISTICS -> {
            val heights = listOf(0.58f, 0.44f, 0.30f)
            heights.forEachIndexed { index, top ->
                val left = 0.26f + index * 0.19f
                drawRect(
                    tint,
                    topLeft = Offset(box(left), box(top)),
                    size = Size(box(0.11f), box(0.84f - top)),
                )
            }
        }
        NavGlyphKind.SETTINGS -> {
            line(0.18f, 0.36f, 0.82f, 0.36f, tint, w, StrokeCap.Butt)
            drawRect(
                tint,
                topLeft = Offset(box(0.36f), box(0.27f)),
                size = Size(box(0.13f), box(0.18f)),
            )
            line(0.18f, 0.66f, 0.82f, 0.66f, tint, w, StrokeCap.Butt)
            drawRect(
                tint,
                topLeft = Offset(box(0.56f), box(0.57f)),
                size = Size(box(0.13f), box(0.18f)),
            )
        }
    }
}

/** 纸感极简：细线、留白，像钢笔在纸上画的草图。 */
private fun DrawScope.drawPaper(kind: NavGlyphKind, tint: Color, selected: Boolean) {
    val w = 1.25.dp.toPx()
    if (selected) {
        when (kind) {
            NavGlyphKind.HOME -> houseSilhouette(tint, box(0.025f))
            NavGlyphKind.LEDGER -> slipSilhouette(tint, box(0.02f))
            NavGlyphKind.STATISTICS -> barsSilhouette(tint, box(0.012f))
            NavGlyphKind.SETTINGS -> slidersFilled(tint, w * 2.2f, 0.255f, square = false)
        }
        return
    }
    when (kind) {
        NavGlyphKind.HOME -> {
            polyline(
                listOf(0.16f to 0.50f, 0.50f to 0.22f, 0.84f to 0.50f),
                tint,
                w,
                StrokeCap.Butt,
            )
            polyline(
                listOf(0.26f to 0.50f, 0.26f to 0.80f, 0.74f to 0.80f, 0.74f to 0.50f),
                tint,
                w,
                StrokeCap.Butt,
            )
        }
        NavGlyphKind.LEDGER -> {
            line(0.24f, 0.30f, 0.76f, 0.30f, tint, w, StrokeCap.Butt)
            line(0.24f, 0.50f, 0.66f, 0.50f, tint, w, StrokeCap.Butt)
            line(0.24f, 0.70f, 0.54f, 0.70f, tint, w, StrokeCap.Butt)
        }
        NavGlyphKind.STATISTICS -> {
            val heights = listOf(0.54f, 0.40f, 0.26f)
            heights.forEachIndexed { index, top ->
                val x = 0.30f + index * 0.20f
                line(x, 0.80f, x, top, tint, w, StrokeCap.Butt)
            }
            drawCircle(tint, box(0.030f), Offset(box(0.70f), box(0.20f)))
        }
        NavGlyphKind.SETTINGS -> {
            line(0.18f, 0.38f, 0.82f, 0.38f, tint, w, StrokeCap.Butt)
            drawCircle(tint, box(0.062f), Offset(box(0.42f), box(0.38f)), style = Stroke(width = w))
            line(0.18f, 0.64f, 0.82f, 0.64f, tint, w, StrokeCap.Butt)
            drawCircle(tint, box(0.062f), Offset(box(0.62f), box(0.64f)), style = Stroke(width = w))
        }
    }
}

/** 肥鱼：粗细居中的圆头线条，配一点金色，跟她的发带呼应。 */
private fun DrawScope.drawOcean(kind: NavGlyphKind, tint: Color) {
    val w = 2.1.dp.toPx()
    when (kind) {
        NavGlyphKind.HOME -> {
            polyline(listOf(0.16f to 0.50f, 0.50f to 0.21f, 0.84f to 0.50f), tint, w, StrokeCap.Round)
            polyline(
                listOf(0.25f to 0.50f, 0.25f to 0.82f, 0.75f to 0.82f, 0.75f to 0.50f),
                tint,
                w,
                StrokeCap.Round,
            )
            wavyLine(0.33f, 0.67f, 0.64f, 0.035f, tint, w * 0.85f, cycles = 1f)
        }
        NavGlyphKind.LEDGER -> {
            wavyLine(0.24f, 0.76f, 0.32f, 0.045f, tint, w * 0.9f, cycles = 1.2f)
            wavyLine(0.24f, 0.68f, 0.52f, 0.045f, tint, w * 0.9f, cycles = 1.2f)
            wavyLine(0.24f, 0.58f, 0.72f, 0.040f, tint, w * 0.9f, cycles = 1.2f)
        }
        NavGlyphKind.STATISTICS -> {
            listOf(0.58f, 0.44f, 0.30f).forEachIndexed { index, top ->
                val x = 0.30f + index * 0.20f
                line(x, 0.80f, x, top, tint, w * 1.8f)
            }
            wavyLine(0.22f, 0.78f, 0.90f, 0.030f, tint, w * 0.8f, cycles = 1f)
        }
        NavGlyphKind.SETTINGS -> {
            cog(tint, w, teeth = 6)
            drawCircle(WhaleGold, box(0.055f), Offset(box(0.50f), box(0.50f)))
        }
    }
}
