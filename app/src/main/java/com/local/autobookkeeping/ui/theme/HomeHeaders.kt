package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面顶部的美化头卡：四套皮肤各有一套装饰语言，底纹全部由 Canvas 绘制，
 * 不含位图、不响应点击，也不带无障碍语义。标题文字由调用方给，
 * 四个页面对着一张卡换文案，皮肤的装饰语言保持一致。
 *
 * 右上角留给「标注徽章」：薄荷/账本/纸感各按自己的材质画一枚会动的小图标
 * （见 [HeaderBadge]），肥鱼那边仍是像素气泡里的人物贴纸。
 *
 * 版式约束（之前纸感极简压字就是因为缺了这条）：
 * 文字列 `weight(1f)` 吃掉剩余宽度、单行省略，装饰永远待在右侧固定槽位里，
 * 再长的副标题也压不到图案上。
 */
@Composable
fun ThemedHomeHeader(
    modifier: Modifier = Modifier,
    title: String = HOME_HEADER_TITLE,
    subtitle: String = HOME_HEADER_SUBTITLE,
    page: HeaderPage = HeaderPage.HOME,
) {
    when (appVisualTokens.headerDecor) {
        HeaderDecor.MINT_SPROUT -> MintSproutHeader(modifier, title, subtitle, page)
        HeaderDecor.RECEIPT_STUB -> ReceiptStubHeader(modifier, title, subtitle, page)
        HeaderDecor.PAPER_RULE -> PaperRuleHeader(modifier, title, subtitle, page)
        HeaderDecor.OCEAN_WAVE -> OceanWaveHeader(modifier, title, subtitle, page)
    }
}

private const val HOME_HEADER_TITLE = "今天"
private const val HOME_HEADER_SUBTITLE = "吃好每一顿，记好每一笔"

/** 头卡正文：标题 + 副标题，都只占一行，宽度由外面 weight 分配。 */
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 三套非肥鱼皮肤共用的头卡骨架：文字占剩余宽度、徽章钉在右侧槽位。 */
@Composable
private fun BadgedHeaderRow(
    title: String,
    subtitle: String,
    titleColor: Color,
    page: HeaderPage,
    startPadding: Dp = 18.dp,
    leading: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = startPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        HeaderTitle(
            title = title,
            subtitle = subtitle,
            titleColor = titleColor,
            subtitleColor = titleColor.copy(alpha = 0.62f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        headerBadgeFor(appVisualTokens.headerDecor, page)?.let { kind ->
            HeaderBadge(kind = kind)
        }
    }
}

/** 清透薄荷：晨露般的薄荷渐层 + 两团柔光，右侧一枚玻璃露珠徽章。 */
@Composable
private fun MintSproutHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    page: HeaderPage,
) {
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
        BadgedHeaderRow(title, subtitle, titleColor, page)
    }
}

/** 小票账本：纸面 + 上下齿孔虚线，右侧一枚带齿孔的纸签徽章。 */
@Composable
private fun ReceiptStubHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    page: HeaderPage,
) {
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
            // 上一道虚线让开标题行（原来是 h*0.14，正切在字上），改到 0.10 并收短
            listOf(h * 0.10f, h * 0.88f).forEach { y ->
                drawLine(
                    Color(0xFFBFB9A8),
                    Offset(w * 0.05f, y),
                    Offset(w * 0.70f, y),
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
        }
        BadgedHeaderRow(title, subtitle, titleColor, page)
    }
}

/** 纸感极简：米色纸面 + 左侧一道深绿短竖线，右侧一片墨线标注徽章。 */
@Composable
private fun PaperRuleHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    page: HeaderPage,
) {
    val titleColor = Color(0xFF18372B)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(9.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color(0xFFF8F4E9))
        }
        BadgedHeaderRow(title, subtitle, titleColor, page, startPadding = 16.dp) {
            // 这道竖线原来按卡片宽度的 3.5% 定位，横屏/平板上会直接压进标题第一个字；
            // 改成跟着文字排版走的固定宽度色块，永远不会跑到字上
            Box(
                Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .background(Color(0xFF2E5A46).copy(alpha = 0.80f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(9.dp))
        }
    }
}

/** 肥鱼：沿用深海横幅，右侧是她自己的循环动画小图。 */
@Composable
private fun OceanWaveHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    page: HeaderPage,
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
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FishHeaderSticker(kind = page.stickerKind())
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
