package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 页面顶部的美化头卡：四套皮肤各有一套装饰语言，全部由 Canvas 绘制，
 * 不含位图、不响应点击，也不带无障碍语义。标题文字由调用方给，
 * 四个页面对着一张卡换文案，皮肤的装饰语言保持一致。
 *
 * [sticker] 只有肥鱼皮肤用得上：右上角气泡里那张像素贴纸，四页各一句台词。
 */
@Composable
fun ThemedHomeHeader(
    modifier: Modifier = Modifier,
    title: String = HOME_HEADER_TITLE,
    subtitle: String = HOME_HEADER_SUBTITLE,
    sticker: HeaderStickerKind = HeaderStickerKind.HOME,
) {
    when (appVisualTokens.headerDecor) {
        HeaderDecor.MINT_SPROUT -> MintSproutHeader(modifier, title, subtitle)
        HeaderDecor.RECEIPT_STUB -> ReceiptStubHeader(modifier, title, subtitle)
        HeaderDecor.PAPER_RULE -> PaperRuleHeader(modifier, title, subtitle)
        HeaderDecor.OCEAN_WAVE -> OceanWaveHeader(modifier, title, subtitle, sticker)
    }
}

private const val HOME_HEADER_TITLE = "今天"
private const val HOME_HEADER_SUBTITLE = "吃好每一顿，记好每一笔"

@Composable
private fun HeaderTitle(
    title: String,
    subtitle: String,
    titleColor: Color,
    subtitleColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
    }
}

/** 清透薄荷：晨露般的薄荷渐层 + 两团柔光 + 右侧一株嫩芽。 */
@Composable
private fun MintSproutHeader(modifier: Modifier = Modifier, title: String, subtitle: String) {
    val titleColor = Color(0xFF0B493A)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFFEFFBF5), Color(0xFFD2EEE2))),
            )
            drawCircle(Color.White.copy(alpha = 0.50f), h * 0.30f, Offset(w * 0.88f, h * 0.16f))
            drawCircle(Color.White.copy(alpha = 0.32f), h * 0.18f, Offset(w * 0.96f, h * 0.66f))

            val wave = Path().apply {
                moveTo(0f, h * 0.88f)
                cubicTo(w * 0.24f, h * 0.80f, w * 0.40f, h * 0.98f, w * 0.62f, h * 0.90f)
                cubicTo(w * 0.80f, h * 0.84f, w * 0.90f, h * 0.94f, w, h * 0.88f)
            }
            drawPath(
                wave,
                Color(0xFF8FCBB2).copy(alpha = 0.55f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(Color(0xFF8FCBB2).copy(alpha = 0.35f), h * 0.022f, Offset(w * 0.30f, h * 0.93f))
            drawCircle(Color(0xFF8FCBB2).copy(alpha = 0.25f), h * 0.014f, Offset(w * 0.20f, h * 0.90f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeaderTitle(title, subtitle, titleColor, titleColor.copy(alpha = 0.66f))
            MintSproutEmblem()
        }
    }
}

@Composable
private fun MintSproutEmblem() {
    Canvas(Modifier.size(44.dp)) {
        val d = size.minDimension
        drawCircle(Color.White.copy(alpha = 0.62f), d / 2f)
        drawCircle(Color(0xFF7FC3A8).copy(alpha = 0.30f), d / 2f, style = Stroke(width = 1.dp.toPx()))
        val stem = Path().apply {
            moveTo(d * 0.50f, d * 0.74f)
            lineTo(d * 0.50f, d * 0.44f)
        }
        drawPath(stem, Color(0xFF2D7D62), style = Stroke(width = d * 0.055f, cap = StrokeCap.Round))
        val leftLeaf = Path().apply {
            moveTo(d * 0.49f, d * 0.58f)
            cubicTo(d * 0.34f, d * 0.56f, d * 0.28f, d * 0.44f, d * 0.30f, d * 0.34f)
            cubicTo(d * 0.42f, d * 0.36f, d * 0.48f, d * 0.46f, d * 0.49f, d * 0.58f)
            close()
        }
        drawPath(leftLeaf, Color(0xFF4EA37F))
        val rightLeaf = Path().apply {
            moveTo(d * 0.51f, d * 0.50f)
            cubicTo(d * 0.64f, d * 0.47f, d * 0.72f, d * 0.36f, d * 0.70f, d * 0.26f)
            cubicTo(d * 0.58f, d * 0.29f, d * 0.52f, d * 0.39f, d * 0.51f, d * 0.50f)
            close()
        }
        drawPath(rightLeaf, Color(0xFF6FBE9B))
    }
}

/** 小票账本：纸面 + 上下齿孔虚线 + 右侧一枚圆形印章。 */
@Composable
private fun ReceiptStubHeader(modifier: Modifier = Modifier, title: String, subtitle: String) {
    val titleColor = Color(0xFF243B32)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFFFFFEFA))
            drawRect(
                Color(0xFFDAD5C6),
                style = Stroke(width = 1.dp.toPx()),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            listOf(h * 0.14f, h * 0.84f).forEach { y ->
                drawLine(
                    Color(0xFFBFB9A8),
                    Offset(w * 0.05f, y),
                    Offset(w * 0.78f, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            // 齿孔：上下各一排小半圆
            val notch = Color(0xFFF1F0EA)
            val step = w / 18f
            for (i in 1..17) {
                val cx = step * i
                drawCircle(notch, h * 0.030f, Offset(cx, 0f))
                drawCircle(notch, h * 0.030f, Offset(cx, h))
            }
            // 印章
            val stamp = Offset(w * 0.855f, h * 0.50f)
            drawCircle(Color(0xFF6E8C7C).copy(alpha = 0.55f), h * 0.185f, stamp, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(Color(0xFF6E8C7C).copy(alpha = 0.34f), h * 0.135f, stamp, style = Stroke(width = 0.8.dp.toPx()))
            drawLine(
                Color(0xFF6E8C7C).copy(alpha = 0.55f),
                Offset(stamp.x - h * 0.075f, stamp.y),
                Offset(stamp.x + h * 0.075f, stamp.y),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                Color(0xFF6E8C7C).copy(alpha = 0.40f),
                Offset(stamp.x - h * 0.055f, stamp.y + h * 0.062f),
                Offset(stamp.x + h * 0.055f, stamp.y + h * 0.062f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderTitle(title, subtitle, titleColor, titleColor.copy(alpha = 0.62f))
        }
    }
}

/** 纸感极简：米色纸面 + 左侧一道深绿短竖线 + 右侧三行渐短的横线。 */
@Composable
private fun PaperRuleHeader(modifier: Modifier = Modifier, title: String, subtitle: String) {
    val titleColor = Color(0xFF18372B)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(9.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFFF8F4E9))
            drawLine(
                Color(0xFF2E5A46).copy(alpha = 0.80f),
                Offset(w * 0.035f, h * 0.30f),
                Offset(w * 0.035f, h * 0.70f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val rules = listOf(0.34f to 0.26f, 0.46f to 0.19f, 0.58f to 0.12f)
            rules.forEachIndexed { index, (y, len) ->
                drawLine(
                    Color(0xFFB9C2B4).copy(alpha = 0.62f - index * 0.12f),
                    Offset(w * 0.70f, h * y),
                    Offset(w * 0.70f + w * len, h * y),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderTitle(title, subtitle, titleColor, titleColor.copy(alpha = 0.60f))
        }
    }
}

/** 肥鱼：沿用深海横幅，右侧是她自己的循环动画小图。 */
@Composable
private fun OceanWaveHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    sticker: HeaderStickerKind,
) {
    WhaleWaveHeader(modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeaderTitle(
                title = title,
                subtitle = subtitle,
                titleColor = MaterialTheme.colorScheme.onPrimary,
                subtitleColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
            )
            FishHeaderSticker(kind = sticker)
        }
    }
}

/** 头卡下方的小装饰条：给「最近记录」这类小标题用，随皮肤换形状。 */
@Composable
fun HeaderAccentMark(modifier: Modifier = Modifier) {
    when (appVisualTokens.headerDecor) {
        HeaderDecor.MINT_SPROUT -> SproutMark(modifier)
        HeaderDecor.RECEIPT_STUB -> ReceiptMark(modifier)
        HeaderDecor.PAPER_RULE -> RuleMark(modifier)
        HeaderDecor.OCEAN_WAVE -> WaveMark(modifier, width = 20.dp)
    }
}

@Composable
private fun SproutMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 14.dp, height = 14.dp)) {
        val d = size.minDimension
        drawCircle(Color(0xFF2D7D62).copy(alpha = 0.18f), d / 2f)
        val leaf = Path().apply {
            moveTo(d * 0.50f, d * 0.78f)
            cubicTo(d * 0.22f, d * 0.72f, d * 0.24f, d * 0.40f, d * 0.50f, d * 0.30f)
            cubicTo(d * 0.76f, d * 0.40f, d * 0.78f, d * 0.72f, d * 0.50f, d * 0.78f)
            close()
        }
        drawPath(leaf, Color(0xFF2D7D62))
    }
}

@Composable
private fun ReceiptMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 13.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.94f)
            lineTo(w * 0.08f, h * 0.10f)
            lineTo(w * 0.92f, h * 0.10f)
            lineTo(w * 0.92f, h * 0.94f)
            lineTo(w * 0.78f, h * 0.80f)
            lineTo(w * 0.64f, h * 0.94f)
            lineTo(w * 0.50f, h * 0.80f)
            lineTo(w * 0.36f, h * 0.94f)
            lineTo(w * 0.22f, h * 0.80f)
            close()
        }
        drawPath(path, Color(0xFF356A58), style = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round))
        drawLine(
            Color(0xFF356A58).copy(alpha = 0.55f),
            Offset(w * 0.28f, h * 0.36f),
            Offset(w * 0.72f, h * 0.36f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun RuleMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 16.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        listOf(0.20f, 0.50f, 0.80f).forEachIndexed { index, y ->
            drawLine(
                Color(0xFF234C3C).copy(alpha = 0.75f - index * 0.16f),
                Offset(0f, h * y),
                Offset(w * (1f - index * 0.22f), h * y),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
