package com.local.autobookkeeping.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 图例色块的形状，跟各皮肤的装饰语言一致。 */
enum class ChartLegendMark {
    /** 圆点，清透薄荷的露珠。 */
    CIRCLE,

    /** 方块，小票上的印刷色块。 */
    SQUARE,

    /** 短墨线，纸感的划线批注。 */
    RULE,

    /** 像素块，肥鱼的 8-bit 图元。 */
    PIXEL,
}

/**
 * 一套皮肤的统计图表色板。
 *
 * [sliceGapDegrees] 是相邻切片之间留的缺口角度：小票像纸上剪开、纸感留一道缝、
 * 肥鱼留白边，薄荷则让切片严丝合缝地连成一片。
 */
@Immutable
data class SkinChartPalette(
    val colors: List<Color>,
    val sliceGapDegrees: Float = 0f,
    val rimColor: Color? = null,
    val legendMark: ChartLegendMark = ChartLegendMark.CIRCLE,
) {
    /** 按金额降序的序号取色，分类数超过色板长度时循环。 */
    fun colorAt(index: Int): Color = colors[Math.floorMod(index, colors.size)]
}

/**
 * 四套皮肤各自的统计色板。
 *
 * 排在第一位的颜色一定是该皮肤的主色，所以「占比最大的分类」永远跟主题同调；
 * 其余颜色按暖冷交替排，相邻切片才不会糊成一片。
 */
fun chartPaletteFor(decor: HeaderDecor): SkinChartPalette = when (decor) {
    HeaderDecor.MINT_SPROUT -> SkinChartPalette(
        colors = listOf(
            Color(0xFF1F7A5E),
            Color(0xFF6FBF9E),
            Color(0xFFA9D9C5),
            Color(0xFFE8B86A),
            Color(0xFFE0785F),
            Color(0xFF7C9FD4),
            Color(0xFFB49BD1),
        ),
        sliceGapDegrees = 0f,
        rimColor = null,
        legendMark = ChartLegendMark.CIRCLE,
    )

    HeaderDecor.RECEIPT_STUB -> SkinChartPalette(
        colors = listOf(
            Color(0xFF4E6B58),
            Color(0xFF8C6A4A),
            Color(0xFFB5573F),
            Color(0xFFC9BFA5),
            Color(0xFF2F3A33),
            Color(0xFF4F6B79),
            Color(0xFF9A8F78),
        ),
        sliceGapDegrees = 0.7f,
        rimColor = Color(0xFFDAD5C6),
        legendMark = ChartLegendMark.SQUARE,
    )

    HeaderDecor.PAPER_RULE -> SkinChartPalette(
        colors = listOf(
            Color(0xFF5F7264),
            Color(0xFF93A494),
            Color(0xFFC3B9A0),
            Color(0xFFA6663F),
            Color(0xFF2B3A31),
            Color(0xFF6B7F8C),
            Color(0xFF9C7E9E),
        ),
        sliceGapDegrees = 1.2f,
        rimColor = Color(0xFFB9C2B5),
        legendMark = ChartLegendMark.RULE,
    )

    HeaderDecor.OCEAN_WAVE -> SkinChartPalette(
        colors = listOf(
            Color(0xFF2E5FAE),
            Color(0xFF4E8FD6),
            Color(0xFF7FB6E8),
            Color(0xFF58C3C9),
            Color(0xFFE8A93C),
            Color(0xFFF2C96B),
            Color(0xFF8E7BD6),
        ),
        sliceGapDegrees = 1.6f,
        rimColor = null,
        legendMark = ChartLegendMark.PIXEL,
    )
}

/** 当前皮肤的统计色板。 */
val skinChartPalette: SkinChartPalette
    @Composable get() = chartPaletteFor(appVisualTokens.headerDecor)
