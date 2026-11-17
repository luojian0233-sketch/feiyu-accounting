package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

enum class ThemeStyle {
    CLEAR_MINT,
    RECEIPT_LEDGER,
    PAPER_MINIMAL,
    FATTY_FISH,
}

@Immutable
data class AppVisualTokens(
    val cardShape: Shape,
    val cardElevation: Dp,
    val cardColor: Color,
    val separatorColor: Color,
    val dashedSeparators: Boolean,
    val showFishMascot: Boolean = false,
)

private val LocalAppVisualTokens = staticCompositionLocalOf {
    AppVisualTokens(
        cardShape = RoundedCornerShape(16.dp),
        cardElevation = 1.dp,
        cardColor = Color.White,
        separatorColor = Color(0xFFE2EAE6),
        dashedSeparators = false,
    )
}

val appVisualTokens: AppVisualTokens
    @Composable get() = LocalAppVisualTokens.current

private val clearMintColors = lightColorScheme(
    primary = Color(0xFF23866B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3EA),
    onPrimaryContainer = Color(0xFF0B493A),
    background = Color(0xFFF7FBF9),
    onBackground = Color(0xFF17211E),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF5F1),
)

private val receiptColors = lightColorScheme(
    primary = Color(0xFF356A58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECE6),
    onPrimaryContainer = Color(0xFF243B32),
    background = Color(0xFFF1F0EA),
    onBackground = Color(0xFF252724),
    surface = Color(0xFFFFFEFA),
    surfaceVariant = Color(0xFFE8E6DF),
)

private val paperMinimalColors = lightColorScheme(
    primary = Color(0xFF234C3C),
    onPrimary = Color(0xFFF9FBF4),
    primaryContainer = Color(0xFFDDE5D8),
    onPrimaryContainer = Color(0xFF18372B),
    background = Color(0xFFF3EFE4),
    onBackground = Color(0xFF263029),
    surface = Color(0xFFF8F4E9),
    surfaceVariant = Color(0xFFE8E2D4),
)

// 取自鲸鱼娘的立绘：海军蓝头发、湖蓝渐层、金色小星星、白围裙。
private val fattyFishColors = lightColorScheme(
    primary = Color(0xFF2E5FAE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FB),
    onPrimaryContainer = Color(0xFF12305E),
    secondary = Color(0xFF4E8FD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4F0FD),
    onSecondaryContainer = Color(0xFF16355C),
    tertiary = Color(0xFFE8A93C),
    onTertiary = Color(0xFF3A2500),
    background = Color(0xFFF6FAFF),
    onBackground = Color(0xFF0E1F33),
    surface = Color.White,
    onSurface = Color(0xFF0E1F33),
    surfaceVariant = Color(0xFFE6EFFA),
    onSurfaceVariant = Color(0xFF40566F),
    outline = Color(0xFFC3D6EC),
)

private val AppTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontSize = 40.sp),
    headlineLarge = Typography().headlineLarge.copy(fontSize = 26.sp),
    titleLarge = Typography().titleLarge.copy(fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 16.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 13.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp),
    labelSmall = Typography().labelSmall.copy(fontSize = 11.sp),
)

@Composable
fun AutoBookkeepingTheme(
    style: ThemeStyle,
    content: @Composable () -> Unit,
) {
    val colors = when (style) {
        ThemeStyle.CLEAR_MINT -> clearMintColors
        ThemeStyle.RECEIPT_LEDGER -> receiptColors
        ThemeStyle.PAPER_MINIMAL -> paperMinimalColors
        ThemeStyle.FATTY_FISH -> fattyFishColors
    }
    val shapes = when (style) {
        ThemeStyle.CLEAR_MINT -> Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        )
        ThemeStyle.RECEIPT_LEDGER -> Shapes(
            extraSmall = RoundedCornerShape(1.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(3.dp),
            large = RoundedCornerShape(4.dp),
        )
        ThemeStyle.PAPER_MINIMAL -> Shapes(
            extraSmall = RoundedCornerShape(3.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(10.dp),
        )
        ThemeStyle.FATTY_FISH -> Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
        )
    }
    val visualTokens = when (style) {
        ThemeStyle.CLEAR_MINT -> AppVisualTokens(
            cardShape = RoundedCornerShape(18.dp),
            cardElevation = 2.dp,
            cardColor = Color.White,
            separatorColor = Color(0xFFE2EAE6),
            dashedSeparators = false,
        )
        ThemeStyle.RECEIPT_LEDGER -> AppVisualTokens(
            cardShape = RoundedCornerShape(2.dp),
            cardElevation = 0.dp,
            cardColor = Color(0xFFFFFEFA),
            separatorColor = Color(0xFFB8B6AE),
            dashedSeparators = true,
        )
        ThemeStyle.PAPER_MINIMAL -> AppVisualTokens(
            cardShape = RoundedCornerShape(8.dp),
            cardElevation = 0.dp,
            cardColor = Color(0xFFF8F4E9),
            separatorColor = Color(0xFF9AA89D),
            dashedSeparators = false,
        )
        ThemeStyle.FATTY_FISH -> AppVisualTokens(
            cardShape = RoundedCornerShape(22.dp),
            cardElevation = 0.dp,
            cardColor = Color.White,
            separatorColor = Color(0xFFDCE8F6),
            dashedSeparators = false,
            showFishMascot = true,
        )
    }
    CompositionLocalProvider(LocalAppVisualTokens provides visualTokens) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = shapes,
            content = content,
        )
    }
}

@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = appVisualTokens
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = tokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = tokens.cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = tokens.cardElevation),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

@Composable
fun AppCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = appVisualTokens
    Card(
        modifier = modifier,
        shape = tokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = tokens.cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = tokens.cardElevation),
    ) {
        content()
    }
}

@Composable
fun AppSeparator(modifier: Modifier = Modifier) {
    val tokens = appVisualTokens
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = tokens.separatorColor,
            start = androidx.compose.ui.geometry.Offset(0f, center.y),
            end = androidx.compose.ui.geometry.Offset(size.width, center.y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (tokens.dashedSeparators) {
                PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            } else {
                null
            },
        )
    }
}
