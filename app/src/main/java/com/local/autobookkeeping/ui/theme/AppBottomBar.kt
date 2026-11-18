package com.local.autobookkeeping.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.autobookkeeping.ui.navigation.AppDestination

/** 底部导航的皮肤语言，跟首页头卡的装饰一一对应。 */
enum class BottomBarStyle {
    MINT_PILL,
    RECEIPT_TICKET,
    PAPER_LINE,
    OCEAN_STICKER,
}

fun bottomBarStyleFor(decor: HeaderDecor): BottomBarStyle = when (decor) {
    HeaderDecor.MINT_SPROUT -> BottomBarStyle.MINT_PILL
    HeaderDecor.RECEIPT_STUB -> BottomBarStyle.RECEIPT_TICKET
    HeaderDecor.PAPER_RULE -> BottomBarStyle.PAPER_LINE
    HeaderDecor.OCEAN_WAVE -> BottomBarStyle.OCEAN_STICKER
}

private enum class Hairline { NONE, DASHED, SOLID }

private data class BottomBarPalette(
    val container: Color,
    val barShape: Shape,
    val elevation: Dp,
    val height: Dp,
    val hairline: Hairline,
    val hairlineColor: Color,
    val iconSelected: Color,
    val iconUnselected: Color,
    val labelSelected: Color,
    val labelUnselected: Color,
    val labelSize: TextUnit,
    val labelLineHeight: TextUnit,
    val labelSpacing: TextUnit,
    val labelWeight: FontWeight,
    val labelWeightSelected: FontWeight = labelWeight,
    val showIndicator: Boolean,
    val indicator: Color,
    val indicatorShape: Shape,
    val indicatorWidth: Dp,
    val indicatorHeight: Dp,
    val indicatorBorder: Color?,
    val underline: Boolean,
    val accentStart: Color,
    val accentEnd: Color,
    val entryShape: Shape,
    val entryWidth: Dp,
    val entryHeight: Dp,
    /** 蓝色肥鱼用逐帧像素小图，图标不是 Canvas 画的。 */
    val stickers: Boolean = false,
    val stickerSize: Dp = 36.dp,
)

@Composable
private fun bottomBarPalette(style: BottomBarStyle): BottomBarPalette {
    val colors = MaterialTheme.colorScheme
    val tokens = appVisualTokens
    return when (style) {
        // 薄荷：白底浮起，灰绿未选中 + 淡薄荷胶囊选中的圆润感。
        BottomBarStyle.MINT_PILL -> BottomBarPalette(
            container = colors.surface,
            barShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            elevation = 10.dp,
            height = 60.dp,
            hairline = Hairline.NONE,
            hairlineColor = Color.Transparent,
            iconSelected = colors.primary,
            iconUnselected = colors.onSurfaceVariant.copy(alpha = 0.66f),
            labelSelected = colors.primary,
            labelUnselected = colors.onSurfaceVariant.copy(alpha = 0.70f),
            labelSize = 10.5.sp,
            labelLineHeight = 12.5.sp,
            labelSpacing = 0.2.sp,
            labelWeight = FontWeight.Medium,
            showIndicator = true,
            indicator = colors.primaryContainer,
            indicatorShape = RoundedCornerShape(percent = 50),
            indicatorWidth = 50.dp,
            indicatorHeight = 29.dp,
            indicatorBorder = null,
            underline = false,
            accentStart = Color(0xFF2E9C7E),
            accentEnd = Color(0xFF1C6E5A),
            entryShape = CircleShape,
            entryWidth = 44.dp,
            entryHeight = 44.dp,
        )

        // 小票：纸白平铺，直角方框选中，字距拉开像打出来的小票。
        BottomBarStyle.RECEIPT_TICKET -> BottomBarPalette(
            container = tokens.cardColor,
            barShape = RectangleShape,
            elevation = 0.dp,
            height = 60.dp,
            hairline = Hairline.DASHED,
            hairlineColor = tokens.separatorColor,
            iconSelected = colors.primary,
            iconUnselected = colors.onSurfaceVariant.copy(alpha = 0.58f),
            labelSelected = colors.primary,
            labelUnselected = colors.onSurfaceVariant.copy(alpha = 0.66f),
            labelSize = 10.sp,
            labelLineHeight = 12.sp,
            labelSpacing = 1.4.sp,
            labelWeight = FontWeight.Medium,
            showIndicator = true,
            indicator = Color.Transparent,
            indicatorShape = RoundedCornerShape(3.dp),
            indicatorWidth = 32.dp,
            indicatorHeight = 30.dp,
            indicatorBorder = colors.primary,
            underline = false,
            accentStart = colors.primary,
            accentEnd = colors.primary,
            entryShape = RoundedCornerShape(3.dp),
            entryWidth = 46.dp,
            entryHeight = 34.dp,
        )

        // 纸感：完全平铺在纸上，只用一条墨色细线标记当前页。
        BottomBarStyle.PAPER_LINE -> BottomBarPalette(
            container = tokens.cardColor,
            barShape = RectangleShape,
            elevation = 0.dp,
            height = 60.dp,
            hairline = Hairline.SOLID,
            hairlineColor = tokens.separatorColor.copy(alpha = 0.55f),
            iconSelected = colors.onBackground,
            iconUnselected = colors.onBackground.copy(alpha = 0.52f),
            labelSelected = colors.onBackground,
            labelUnselected = colors.onBackground.copy(alpha = 0.55f),
            labelSize = 10.sp,
            labelLineHeight = 12.sp,
            labelSpacing = 1.6.sp,
            labelWeight = FontWeight.Normal,
            showIndicator = false,
            indicator = Color.Transparent,
            indicatorShape = RectangleShape,
            indicatorWidth = 34.dp,
            indicatorHeight = 24.dp,
            indicatorBorder = null,
            underline = true,
            accentStart = colors.primary,
            accentEnd = colors.primary,
            entryShape = CircleShape,
            entryWidth = 42.dp,
            entryHeight = 42.dp,
        )

        // 肥鱼：白色浮起条，选中给一颗淡蓝气泡，图标是逐帧像素小图。
        BottomBarStyle.OCEAN_STICKER -> BottomBarPalette(
            container = Color.White,
            barShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            elevation = 10.dp,
            height = 76.dp,
            hairline = Hairline.NONE,
            hairlineColor = Color.Transparent,
            iconSelected = colors.primary,
            iconUnselected = Color(0xFF6E7F95),
            labelSelected = colors.primary,
            labelUnselected = Color(0xFF74859B),
            labelSize = 10.5.sp,
            labelLineHeight = 12.5.sp,
            labelSpacing = 0.3.sp,
            labelWeight = FontWeight.Medium,
            labelWeightSelected = FontWeight.SemiBold,
            showIndicator = true,
            indicator = Color(0xFFDFECFF),
            indicatorShape = CircleShape,
            indicatorWidth = 48.dp,
            indicatorHeight = 48.dp,
            indicatorBorder = null,
            underline = false,
            accentStart = Color(0xFF3E7BD4),
            accentEnd = Color(0xFF2A57A4),
            entryShape = CircleShape,
            entryWidth = 46.dp,
            entryHeight = 46.dp,
            stickers = true,
            stickerSize = 38.dp,
        )
    }
}

@Composable
fun AppBottomBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = appVisualTokens.bottomBarStyle
    val palette = bottomBarPalette(style)
    // 像素小图每次点击完整播一遍；换到别的位置就把令牌清零，动画随之中断。
    var playToken by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<FishStickerKind?>(null) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("bottom-bar"),
        shape = palette.barShape,
        color = palette.container,
        shadowElevation = palette.elevation,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            if (palette.hairline != Hairline.NONE) {
                Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                    drawLine(
                        color = palette.hairlineColor,
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (palette.hairline == Hairline.DASHED) {
                            androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                            )
                        } else {
                            null
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(palette.height)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppDestination.entries.forEach { destination ->
                    BarItem(
                        destination = destination,
                        selected = destination == current,
                        palette = palette,
                        playToken = if (playing == destination.sticker) playToken else 0,
                        onClick = {
                            destination.sticker?.let {
                                playing = it
                                playToken += 1
                            }
                            onSelect(destination)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(
    destination: AppDestination,
    selected: Boolean,
    palette: BottomBarPalette,
    playToken: Int,
    onClick: () -> Unit,
) {
    if (destination == AppDestination.ENTRY) {
        EntryButton(selected, palette, onClick)
        return
    }
    val animation = tween<Color>(durationMillis = 220)
    val iconColor by animateColorAsState(
        targetValue = if (selected) palette.iconSelected else palette.iconUnselected,
        animationSpec = animation,
        label = "nav-icon",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) palette.labelSelected else palette.labelUnselected,
        animationSpec = animation,
        label = "nav-label",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "nav-indicator",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 0.92f,
        animationSpec = tween(durationMillis = 240),
        label = "nav-scale",
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag("nav-${destination.name}")
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(palette.indicatorWidth)
                .height(palette.indicatorHeight)
                .background(
                    color = if (palette.showIndicator) {
                        palette.indicator.copy(alpha = palette.indicator.alpha * indicatorAlpha)
                    } else {
                        Color.Transparent
                    },
                    shape = palette.indicatorShape,
                )
                .then(
                    if (palette.indicatorBorder != null) {
                        Modifier.border(
                            width = 1.2.dp,
                            color = palette.indicatorBorder.copy(alpha = indicatorAlpha),
                            shape = palette.indicatorShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val sticker = destination.sticker
            if (palette.stickers && sticker != null) {
                FishSticker(
                    kind = sticker,
                    playToken = playToken,
                    size = palette.stickerSize,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = if (selected) 1f else 0.88f
                    },
                )
            } else {
                CompositionLocalProvider(LocalContentColor provides iconColor) {
                    NavGlyph(
                        kind = destination.glyph,
                        selected = selected,
                        size = 21.dp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    )
                }
            }
        }
        Text(
            text = destination.label,
            color = labelColor,
            fontSize = palette.labelSize,
            lineHeight = palette.labelLineHeight,
            letterSpacing = palette.labelSpacing,
            fontWeight = if (selected) palette.labelWeightSelected else palette.labelWeight,
            maxLines = 1,
            modifier = Modifier.padding(top = 1.dp),
        )
        if (palette.underline) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .width(15.dp)
                    .height(1.4.dp)
                    .background(
                        color = palette.labelSelected.copy(alpha = indicatorAlpha),
                        shape = RectangleShape,
                    ),
            )
        }
    }
}

@Composable
private fun RowScope.EntryButton(
    selected: Boolean,
    palette: BottomBarPalette,
    onClick: () -> Unit,
) {
    val ring by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "entry-ring",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(durationMillis = 220),
        label = "entry-scale",
    )
    Box(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(palette.entryWidth + 8.dp, palette.entryHeight + 8.dp)
                .border(
                    width = 1.5.dp,
                    color = palette.accentStart.copy(alpha = 0.22f * ring),
                    shape = if (palette.entryShape == CircleShape) CircleShape else RoundedCornerShape(7.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(palette.entryWidth)
                    .height(palette.entryHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(palette.entryShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(palette.accentStart, palette.accentEnd),
                        ),
                        shape = palette.entryShape,
                    )
                    .clickable(onClick = onClick)
                    .testTag("nav-${AppDestination.ENTRY.name}")
                    .semantics { contentDescription = "记一笔" },
                contentAlignment = Alignment.Center,
            ) {
                PlusGlyph(color = Color.White, size = 20.dp, stroke = 2.4.dp)
            }
        }
    }
}

/** 中央「记一笔」的加号，自绘避免字体缺字。 */
@Composable
fun PlusGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    stroke: Dp = 2.6.dp,
) {
    Canvas(modifier.size(size)) {
        val half = this.size.minDimension * 0.30f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(center.x - half, center.y),
            end = androidx.compose.ui.geometry.Offset(center.x + half, center.y),
            strokeWidth = stroke.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(center.x, center.y - half),
            end = androidx.compose.ui.geometry.Offset(center.x, center.y + half),
            strokeWidth = stroke.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** 底部导航项到 Canvas 图标类型的映射。 */
val AppDestination.glyph: NavGlyphKind
    get() = when (this) {
        AppDestination.HOME -> NavGlyphKind.HOME
        AppDestination.LEDGER -> NavGlyphKind.LEDGER
        AppDestination.STATISTICS -> NavGlyphKind.STATISTICS
        AppDestination.SETTINGS -> NavGlyphKind.SETTINGS
        AppDestination.ENTRY -> NavGlyphKind.HOME
    }

/** 蓝色肥鱼底栏的像素小图，中央「记一笔」没有贴纸。 */
val AppDestination.sticker: FishStickerKind?
    get() = when (this) {
        AppDestination.HOME -> FishStickerKind.HOME
        AppDestination.LEDGER -> FishStickerKind.LEDGER
        AppDestination.STATISTICS -> FishStickerKind.STATISTICS
        AppDestination.SETTINGS -> FishStickerKind.SETTINGS
        AppDestination.ENTRY -> null
    }
