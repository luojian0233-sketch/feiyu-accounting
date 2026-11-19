package com.local.autobookkeeping.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.CategoryTotal
import com.local.autobookkeeping.ui.theme.ChartLegendMark
import com.local.autobookkeeping.ui.theme.appVisualTokens
import com.local.autobookkeeping.ui.theme.skinChartPalette
import java.util.Locale
import kotlin.math.max

data class PieSliceDescriptor(
    val item: CategoryTotal,
    val sweepDegrees: Double,
    val percentage: Double,
    val accessibilityText: String,
)

fun calculatePieSweeps(amounts: List<Long>): List<Double> {
    if (amounts.isEmpty() || amounts.none { it > 0L }) return amounts.map { 0.0 }
    val maximum = amounts.maxOrNull()!!.toDouble()
    val scaledTotal = amounts.sumOf { amount -> max(0.0, amount.toDouble() / maximum) }
    val lastPositive = amounts.indexOfLast { it > 0L }
    var accumulated = 0.0
    return amounts.mapIndexed { index, amount ->
        when {
            amount <= 0L -> 0.0
            index == lastPositive -> (360.0 - accumulated).coerceAtLeast(0.0)
            else -> {
                val sweep = (amount.toDouble() / maximum / scaledTotal * 360.0)
                    .coerceIn(0.0, 360.0 - accumulated)
                accumulated += sweep
                sweep
            }
        }
    }
}

fun pieSliceDescriptors(items: List<CategoryTotal>): List<PieSliceDescriptor> {
    val sweeps = calculatePieSweeps(items.map { it.amountMinor })
    val lastVisible = sweeps.indexOfLast { it > 0.0 }
    var accumulatedPercentage = 0.0
    return items.mapIndexed { index, item ->
        val percentage = when {
            sweeps[index] <= 0.0 -> 0.0
            index == lastVisible -> (100.0 - accumulatedPercentage).coerceAtLeast(0.0)
            else -> (sweeps[index] / 360.0 * 100.0)
                .coerceIn(0.0, 100.0 - accumulatedPercentage)
                .also { accumulatedPercentage += it }
        }
        PieSliceDescriptor(
            item = item,
            sweepDegrees = sweeps[index],
            percentage = percentage,
            accessibilityText = "${item.category}，${formatUnsignedMoney(item.amountMinor)}元，" +
                String.format(Locale.CHINA, "%.1f%%", percentage),
        )
    }
}

/**
 * 分类占比饼图。
 *
 * 颜色跟着皮肤走：按金额降序取色板，所以占比最大的分类永远是该皮肤的主色；
 * 小票/纸感在切片之间留缺口、纸感还带一圈细墨线外框，肥鱼留白边，薄荷连成一片。
 */
@Composable
fun PieChart(
    items: List<CategoryTotal>,
    modifier: Modifier = Modifier,
) {
    val palette = skinChartPalette
    val gapColor = appVisualTokens.cardColor
    val descriptors = pieSliceDescriptors(items)
    val description = descriptors.joinToString("；", transform = PieSliceDescriptor::accessibilityText)
    Canvas(
        modifier
            .testTag("statistics-pie")
            .semantics { contentDescription = description },
    ) {
        var startAngle = -90f
        descriptors.forEachIndexed { index, descriptor ->
            val sweep = descriptor.sweepDegrees.toFloat()
            if (sweep > 0f) {
                drawArc(
                    color = palette.colorAt(index),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                )
                if (palette.sliceGapDegrees > 0f && sweep > palette.sliceGapDegrees * 2f) {
                    // 缺口用卡片底色补，看起来就是从"纸"里切开的
                    drawArc(
                        color = gapColor,
                        startAngle = startAngle + sweep - palette.sliceGapDegrees,
                        sweepAngle = palette.sliceGapDegrees,
                        useCenter = true,
                    )
                }
            }
            startAngle += sweep
        }
        palette.rimColor?.let { rim ->
            val stroke = 1.dp.toPx()
            drawCircle(
                color = rim,
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(stroke),
            )
        }
    }
}

/** 图例色块：圆点 / 方块 / 短墨线 / 像素块，跟皮肤语言一致。 */
@Composable
fun ChartLegendSwatch(
    mark: ChartLegendMark,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val side = if (mark == ChartLegendMark.RULE) 12.dp else 9.dp
    Canvas(modifier.size(side)) {
        when (mark) {
            ChartLegendMark.CIRCLE -> drawCircle(color, radius = size.minDimension / 2f)

            ChartLegendMark.SQUARE -> drawRect(color, size = Size(size.width, size.height))

            ChartLegendMark.RULE -> drawRect(
                color = color,
                topLeft = Offset(0f, (size.height - 2.dp.toPx()) / 2f),
                size = Size(size.width, 2.dp.toPx()),
            )

            ChartLegendMark.PIXEL -> {
                drawRect(color, size = Size(size.width, size.height))
                // 左上角一道浅色高光，读起来才像像素块而不是纯色方块
                drawRect(
                    color = Color.White.copy(alpha = 0.35f),
                    size = Size(size.width * 0.42f, size.height * 0.42f),
                )
            }
        }
    }
}

internal fun formatUnsignedMoney(amountMinor: Long): String =
    "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
