package com.local.autobookkeeping.ui.entry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.autobookkeeping.ui.theme.KeypadFinish
import com.local.autobookkeeping.ui.theme.KeypadPanel
import com.local.autobookkeeping.ui.theme.SkinKeypadStyle
import com.local.autobookkeeping.ui.theme.appVisualTokens
import com.local.autobookkeeping.ui.theme.skinKeypadStyle

/** 金额输入的一个按键，纯数据，方便抽出来单测。 */
sealed interface AmountKey {
    data class Digit(val value: Int) : AmountKey
    data object Dot : AmountKey

    /** 一次补两个零，输 200、500 这类整额快。 */
    data object DoubleZero : AmountKey
    data object Backspace : AmountKey
    data object Clear : AmountKey
}

const val MAX_AMOUNT_INTEGER_DIGITS = 7
const val MAX_AMOUNT_DECIMAL_DIGITS = 2

const val AMOUNT_FIELD_TAG = "amount"
const val AMOUNT_KEYPAD_TAG = "amount-keypad"
const val KEYPAD_DONE_TAG = "keypad-done"

/** 按键的测试标签，也用作无障碍描述的一部分。 */
fun keypadTag(key: AmountKey): String = when (key) {
    is AmountKey.Digit -> "keypad-${key.value}"
    AmountKey.Dot -> "keypad-dot"
    AmountKey.DoubleZero -> "keypad-00"
    AmountKey.Backspace -> "keypad-back"
    AmountKey.Clear -> "keypad-clear"
}

/**
 * 键盘输入的纯规则：整数最多 7 位、小数最多 2 位、只允许一个小数点，
 * 且不会出现 `05` 这种前导零。
 */
fun applyAmountKey(current: String, key: AmountKey): String = when (key) {
    is AmountKey.Digit -> appendAmountDigit(current, key.value)
    AmountKey.Dot -> when {
        current.contains('.') -> current
        current.isEmpty() -> "0."
        else -> "$current."
    }
    AmountKey.DoubleZero -> appendAmountDigit(appendAmountDigit(current, 0), 0)
    AmountKey.Backspace -> current.dropLast(1)
    AmountKey.Clear -> ""
}

private fun appendAmountDigit(current: String, digit: Int): String {
    if (current.contains('.')) {
        val fraction = current.substringAfter('.')
        return if (fraction.length >= MAX_AMOUNT_DECIMAL_DIGITS) current else current + digit
    }
    if (current == "0") return digit.toString()
    if (current.length >= MAX_AMOUNT_INTEGER_DIGITS) return current
    return current + digit
}

/**
 * 局内数字键盘：4×4，`完成` 竖跨两行，收起由调用方处理。
 *
 * 键位、尺寸、交互四套皮肤完全一致，只有材质随皮肤变化。
 */
@Composable
fun AmountKeypad(
    visible: Boolean,
    onKey: (AmountKey) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = skinKeypadStyle
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        KeypadPanel(style = style, onKey = onKey, onDone = onDone)
    }
}

/** 金额展示框：点击唤出局内键盘，本身不接系统输入法。 */
@Composable
fun AmountFieldDisplay(
    amountText: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appVisualTokens
    val shape = tokens.cardShape
    val accent = MaterialTheme.colorScheme.primary
    val blank = amountText.isEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.cardColor)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(AMOUNT_FIELD_TAG),
    ) {
        Text(
            text = "金额（元）",
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "¥",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (blank) "0.00" else amountText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (blank) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (focused) {
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun KeypadPanel(
    style: SkinKeypadStyle,
    onKey: (AmountKey) -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = style.panelCorner, topEnd = style.panelCorner)
    val strip = panelStripHeight(style)
    // 横屏/平板上一块通铺键盘会把按键拉成宽条，所以面板限宽居中
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (style.panelColors.size > 1) {
                        Brush.verticalGradient(style.panelColors)
                    } else {
                        SolidColor(style.panelColors.first())
                    },
                ),
        ) {
            KeypadDecor(style = style, modifier = Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag(AMOUNT_KEYPAD_TAG),
            ) {
                KeypadHeader(style = style, modifier = Modifier.fillMaxWidth().height(strip))
                KeypadGrid(style = style, onKey = onKey, onDone = onDone)
            }
        }
    }
}

private fun panelStripHeight(style: SkinKeypadStyle): androidx.compose.ui.unit.Dp = when (style.panel) {
    KeypadPanel.RECEIPT_PERFORATED -> 48.dp
    KeypadPanel.PAPER_RULED -> 40.dp
    else -> 36.dp
}

@Composable
private fun KeypadHeader(style: SkinKeypadStyle, modifier: Modifier = Modifier) {
    val plate = style.plate ?: return
    Box(modifier) {
        if (style.panel == KeypadPanel.RECEIPT_PERFORATED) {
            Text(
                text = "—  小 计 录 入  —",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = Color(0xFF9A8F78),
            )
        }
        Text(
            text = plate,
            modifier = Modifier
                .align(
                    if (style.panel == KeypadPanel.RECEIPT_PERFORATED) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                )
                .padding(horizontal = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = style.plateColor,
        )
    }
}

@Composable
private fun KeypadDecor(style: SkinKeypadStyle, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (style.panel) {
            KeypadPanel.MINT_GRADIENT -> {
                drawRoundRect(
                    color = Color(0xFFBDE3D2),
                    topLeft = Offset(w / 2 - 18.dp.toPx(), 9.dp.toPx()),
                    size = Size(36.dp.toPx(), 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }

            KeypadPanel.RECEIPT_PERFORATED -> {
                // 上沿撕口：用背景色在半圆位置上挖洞
                val radius = 5.dp.toPx()
                var x = radius
                while (x < w) {
                    drawCircle(color = background, radius = radius, center = Offset(x, 0f))
                    x += 13.dp.toPx()
                }
                drawRoundRect(
                    color = Color(0xFFD5D0C1),
                    topLeft = Offset(1.dp.toPx(), 12.dp.toPx()),
                    size = Size(w - 2.dp.toPx(), h - 13.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }

            KeypadPanel.PAPER_RULED -> {
                drawLine(
                    color = Color(0xFFC9C2AC),
                    start = Offset(0f, 0.5.dp.toPx()),
                    end = Offset(w, 0.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = Color(0xFFE6DECB),
                    start = Offset(0f, 1.5.dp.toPx()),
                    end = Offset(w, 1.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                // 左侧墨线，和首页头卡的纸感规则线同源
                drawLine(
                    color = Color(0xFF8FA594),
                    start = Offset(7.dp.toPx(), 14.dp.toPx()),
                    end = Offset(7.dp.toPx(), h - 14.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            KeypadPanel.FISH_DEEP_SEA -> {
                drawRoundRect(
                    color = Color(0x40FFFFFF),
                    topLeft = Offset(w / 2 - 18.dp.toPx(), 10.dp.toPx()),
                    size = Size(36.dp.toPx(), 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
                drawCircle(Color(0x1CFFFFFF), radius = 6.dp.toPx(), center = Offset(w - 46.dp.toPx(), 26.dp.toPx()))
                drawCircle(Color(0x16FFFFFF), radius = 3.5.dp.toPx(), center = Offset(w - 66.dp.toPx(), 18.dp.toPx()))
                drawSparkle(
                    center = Offset(w - 116.dp.toPx(), 24.dp.toPx()),
                    radius = 6.dp.toPx(),
                    color = Color(0xFFF2C96B),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(
    center: Offset,
    radius: Float,
    color: Color,
) {
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

@Composable
private fun KeypadGrid(
    style: SkinKeypadStyle,
    onKey: (AmountKey) -> Unit,
    onDone: () -> Unit,
) {
    val gap = 8.dp
    val rowHeight = 56.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val keyWidth = (maxWidth - gap * 3) / 4
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight * 4 + gap * 3),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
                    .chunked(3)
                    .forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(rowHeight),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            row.forEach { digit ->
                                DigitKey(
                                    digit = digit,
                                    style = style,
                                    onClick = { onKey(AmountKey.Digit(digit)) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                Row(
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    LabelKey(
                        label = ".",
                        style = style,
                        key = AmountKey.Dot,
                        onClick = { onKey(AmountKey.Dot) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    DigitKey(
                        digit = 0,
                        style = style,
                        onClick = { onKey(AmountKey.Digit(0)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    LabelKey(
                        label = "00",
                        style = style,
                        key = AmountKey.DoubleZero,
                        onClick = { onKey(AmountKey.DoubleZero) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Column(
                modifier = Modifier.width(keyWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                UtilityKey(
                    style = style,
                    key = AmountKey.Backspace,
                    onClick = { onKey(AmountKey.Backspace) },
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                ) {
                    BackspaceIcon(color = style.utilityInk, modifier = Modifier.width(25.dp).height(18.dp))
                }
                DoneKey(
                    style = style,
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                UtilityKey(
                    style = style,
                    key = AmountKey.Clear,
                    onClick = { onKey(AmountKey.Clear) },
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                ) {
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 14.sp,
                        color = style.utilityInk,
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitKey(
    digit: Int,
    style: SkinKeypadStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeySurface(
        style = style,
        utility = false,
        onClick = onClick,
        modifier = modifier.testTag(keypadTag(AmountKey.Digit(digit))),
    ) {
        Text(
            text = digit.toString(),
            fontFamily = style.digitFont,
            fontSize = 22.sp,
            fontWeight = if (style.digitWeightBold) FontWeight.Bold else FontWeight.Normal,
            color = style.inkColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun LabelKey(
    label: String,
    style: SkinKeypadStyle,
    key: AmountKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeySurface(
        style = style,
        utility = false,
        onClick = onClick,
        modifier = modifier.testTag(keypadTag(key)),
    ) {
        Text(
            text = label,
            fontFamily = style.digitFont,
            fontSize = if (label.length > 1) 19.sp else 22.sp,
            fontWeight = if (style.digitWeightBold) FontWeight.Bold else FontWeight.Normal,
            color = style.inkColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun UtilityKey(
    style: SkinKeypadStyle,
    key: AmountKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    KeySurface(
        style = style,
        utility = true,
        onClick = onClick,
        modifier = modifier
            .testTag(keypadTag(key))
            .semantics {
                contentDescription = when (key) {
                    AmountKey.Backspace -> "退格"
                    AmountKey.Clear -> "清空金额"
                    else -> ""
                }
            },
    ) {
        content()
    }
}

@Composable
private fun KeySurface(
    style: SkinKeypadStyle,
    utility: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(style.keyCorner)
    val fill = when {
        utility && pressed -> style.utilityPressedColor
        utility -> style.utilityColor
        pressed -> style.keyPressedColor
        else -> style.keyColor
    }
    val rim = when {
        utility -> style.utilityRim
        pressed -> style.keyPressedRim
        else -> style.keyRim
    }
    val elevation = if (pressed) 0.dp else style.keyElevation
    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(fill)
            .then(if (rim != null) Modifier.border(1.dp, rim, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun DoneKey(
    style: SkinKeypadStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(style.keyCorner)
    val brush: Brush = when (style.finish) {
        KeypadFinish.MINT_GRADIENT -> Brush.verticalGradient(
            if (pressed) listOf(Color(0xFF277C5F), Color(0xFF1B6B51))
            else listOf(Color(0xFF2E9373), Color(0xFF1F7A5E)),
        )
        KeypadFinish.FISH_GOLD -> Brush.verticalGradient(
            if (pressed) listOf(Color(0xFFD5972C), Color(0xFFE2B655))
            else listOf(Color(0xFFE8A93C), Color(0xFFF2C96B)),
        )
        KeypadFinish.RECEIPT_STAMP -> SolidColor(
            if (pressed) Color(0xFF445149) else Color(0xFF2F3A33),
        )
        KeypadFinish.PAPER_INK -> SolidColor(
            if (pressed) Color(0xFF1A3B2E) else Color(0xFF234C3C),
        )
    }
    val elevated = style.finish == KeypadFinish.MINT_GRADIENT && !pressed
    Box(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(3.dp, shape) else Modifier)
            .clip(shape)
            .background(brush)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(KEYPAD_DONE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (style.finish == KeypadFinish.RECEIPT_STAMP) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                drawRoundRect(
                    color = Color(0xFFF7F4E8),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (style.finish == KeypadFinish.RECEIPT_STAMP) "完 成" else "完成",
                fontSize = if (style.finish == KeypadFinish.RECEIPT_STAMP) 15.sp else 17.sp,
                fontWeight = FontWeight.Bold,
                color = style.finishInk,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            when (style.finish) {
                KeypadFinish.RECEIPT_STAMP -> Text(
                    text = "ENTER",
                    fontSize = 8.sp,
                    color = Color(0xFFC9C4B4),
                )
                KeypadFinish.PAPER_INK -> {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(34.dp).height(1.dp).background(Color(0xFFB9C2B5)))
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun BackspaceIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val tip = 0f
        val body = h * 0.62f
        val right = w
        val top = 0f
        val bottom = h
        val mid = h / 2f
        val path = Path().apply {
            moveTo(tip, mid)
            lineTo(body, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(body, bottom)
            close()
        }
        drawPath(path, color, style = Stroke(width = stroke))
        val ax = body + h * 0.16f
        val bx = right - h * 0.22f
        drawLine(color, Offset(ax, h * 0.30f), Offset(bx, h * 0.70f), strokeWidth = stroke)
        drawLine(color, Offset(ax, h * 0.70f), Offset(bx, h * 0.30f), strokeWidth = stroke)
    }
}
