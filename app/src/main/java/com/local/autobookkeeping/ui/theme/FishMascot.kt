package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 肥鱼皮肤的吉祥物：一条圆胖的小鱼。
 *
 * 纯 Canvas 绘制，不含文字、无障碍语义或点击行为，仅作装饰；尺寸完全由调用方决定。
 */
@Composable
fun FishMascot(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    bodyColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height

        // 尾鳍在身体后面，作为更浅的色块，避免小尺寸下细节糊成一团。
        val tail = Path().apply {
            moveTo(width * 0.62f, height * 0.50f)
            lineTo(width * 0.96f, height * 0.18f)
            lineTo(width * 0.96f, height * 0.82f)
            close()
        }
        drawPath(tail, color = bodyColor.copy(alpha = 0.55f))

        val dorsalFin = Path().apply {
            moveTo(width * 0.30f, height * 0.28f)
            lineTo(width * 0.42f, height * 0.06f)
            lineTo(width * 0.52f, height * 0.28f)
            close()
        }
        drawPath(dorsalFin, color = bodyColor.copy(alpha = 0.55f))

        drawOval(
            color = bodyColor,
            topLeft = Offset(width * 0.04f, height * 0.24f),
            size = Size(width * 0.66f, height * 0.52f),
        )

        drawCircle(
            color = Color.White,
            radius = height * 0.095f,
            center = Offset(width * 0.25f, height * 0.40f),
        )
        drawCircle(
            color = FishEyeColor,
            radius = height * 0.048f,
            center = Offset(width * 0.27f, height * 0.41f),
        )
        drawCircle(
            color = FishCheekColor,
            radius = height * 0.055f,
            center = Offset(width * 0.16f, height * 0.63f),
        )
    }
}

private val FishEyeColor = Color(0xFF123B42)
private val FishCheekColor = Color(0xFFF2887C)
