package com.local.autobookkeeping.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 键盘底板的画法，每套皮肤一种。 */
enum class KeypadPanel {
    /** 白到浅薄荷的渐层，顶部一颗胶囊把手，角落几点露水。 */
    MINT_GRADIENT,

    /** 奶白纸，上沿是撕口，整圈虚线，右上角打印编号。 */
    RECEIPT_PERFORATED,

    /** 米纸，顶部一道纸痕，左侧一条墨线，片头点线。 */
    PAPER_RULED,

    /** 深海渐层，磨砂气泡和金色小星星。 */
    FISH_DEEP_SEA,
}

/** 主键「完成」的画法。 */
enum class KeypadFinish {
    MINT_GRADIENT,
    RECEIPT_STAMP,
    PAPER_INK,
    FISH_GOLD,
}

/**
 * 一套皮肤的局内键盘外观。
 *
 * 键位、尺寸、交互四套完全一致，变的只有材质：圆角、填充、描边、
 * 数字字形和「完成」键的做法。配色与 [SkinChartPalette] 同源，
 * 主色永远出现在「完成」键上。
 */
@Immutable
data class SkinKeypadStyle(
    val panel: KeypadPanel,
    val panelColors: List<Color>,
    val panelCorner: Dp,
    val keyCorner: Dp,
    val keyColor: Color,
    val keyRim: Color?,
    val keyPressedColor: Color,
    val keyPressedRim: Color?,
    val keyElevation: Dp,
    val inkColor: Color,
    val utilityColor: Color,
    val utilityRim: Color?,
    val utilityInk: Color,
    val utilityPressedColor: Color,
    val finish: KeypadFinish,
    val finishInk: Color,
    val digitFont: FontFamily,
    val digitWeightBold: Boolean,
    val plate: String?,
    val plateColor: Color,
    val showHandle: Boolean,
)

/** 当前皮肤的键盘外观。 */
val skinKeypadStyle: SkinKeypadStyle
    @Composable get() = keypadStyleFor(appVisualTokens.headerDecor)

fun keypadStyleFor(decor: HeaderDecor): SkinKeypadStyle = when (decor) {
    HeaderDecor.MINT_SPROUT -> SkinKeypadStyle(
        panel = KeypadPanel.MINT_GRADIENT,
        panelColors = listOf(Color(0xFFFDFEFD), Color(0xFFE7F4ED)),
        panelCorner = 22.dp,
        keyCorner = 16.dp,
        keyColor = Color.White,
        keyRim = Color(0xFFE1EFE8),
        keyPressedColor = Color(0xFFD6EEE2),
        keyPressedRim = Color(0xFF9AD3BC),
        keyElevation = 2.dp,
        inkColor = Color(0xFF17211E),
        utilityColor = Color(0xFFEEF5F1),
        utilityRim = Color(0xFFDFEBE4),
        utilityInk = Color(0xFF2D7D62),
        utilityPressedColor = Color(0xFFDCEAE3),
        finish = KeypadFinish.MINT_GRADIENT,
        finishInk = Color.White,
        digitFont = FontFamily.SansSerif,
        digitWeightBold = false,
        plate = "金额",
        plateColor = Color(0xFF769588),
        showHandle = true,
    )

    HeaderDecor.RECEIPT_STUB -> SkinKeypadStyle(
        panel = KeypadPanel.RECEIPT_PERFORATED,
        panelColors = listOf(Color(0xFFFFFEFA)),
        panelCorner = 0.dp,
        keyCorner = 2.dp,
        keyColor = Color(0xFFFBF8EE),
        keyRim = Color(0xFFCFC9B8),
        keyPressedColor = Color(0xFFE7E1CE),
        keyPressedRim = Color(0xFF8C8878),
        keyElevation = 0.dp,
        inkColor = Color(0xFF2F3A33),
        utilityColor = Color(0xFFF3EFE3),
        utilityRim = Color(0xFFB8B6AE),
        utilityInk = Color(0xFF4E6B58),
        utilityPressedColor = Color(0xFFE7E1CE),
        finish = KeypadFinish.RECEIPT_STAMP,
        finishInk = Color(0xFFF7F4E8),
        digitFont = FontFamily.Monospace,
        digitWeightBold = true,
        plate = "NO.0001",
        plateColor = Color(0xFFB5A98F),
        showHandle = false,
    )

    HeaderDecor.PAPER_RULE -> SkinKeypadStyle(
        panel = KeypadPanel.PAPER_RULED,
        panelColors = listOf(Color(0xFFF7F1E3)),
        panelCorner = 0.dp,
        keyCorner = 6.dp,
        keyColor = Color(0xFFFAF6EC),
        keyRim = Color(0xFFDCD3BD),
        keyPressedColor = Color(0xFFE4DCC8),
        keyPressedRim = Color(0xFF8FA594),
        keyElevation = 0.dp,
        inkColor = Color(0xFF2B3A31),
        utilityColor = Color(0xFFF2ECDD),
        utilityRim = Color(0xFFD8D0BC),
        utilityInk = Color(0xFF5F7264),
        utilityPressedColor = Color(0xFFE4DCC8),
        finish = KeypadFinish.PAPER_INK,
        finishInk = Color(0xFFF9FBF4),
        digitFont = FontFamily.Serif,
        digitWeightBold = false,
        plate = "金额",
        plateColor = Color(0xFF8B998C),
        showHandle = false,
    )

    HeaderDecor.OCEAN_WAVE -> SkinKeypadStyle(
        panel = KeypadPanel.FISH_DEEP_SEA,
        panelColors = listOf(Color(0xFF153157), Color(0xFF3369BC)),
        panelCorner = 24.dp,
        keyCorner = 20.dp,
        keyColor = Color(0x1FFFFFFF),
        keyRim = Color(0x3EFFFFFF),
        keyPressedColor = Color(0x5CFFFFFF),
        keyPressedRim = Color(0xA0FFFFFF),
        keyElevation = 0.dp,
        inkColor = Color(0xFFF2F8FF),
        utilityColor = Color(0x1FFFFFFF),
        utilityRim = Color(0x3EFFFFFF),
        utilityInk = Color(0xFFC7DCF5),
        utilityPressedColor = Color(0x5CFFFFFF),
        finish = KeypadFinish.FISH_GOLD,
        finishInk = Color(0xFF3A2500),
        digitFont = FontFamily.SansSerif,
        digitWeightBold = true,
        plate = "AMOUNT",
        plateColor = Color(0xFF9FC4EC),
        showHandle = true,
    )
}
