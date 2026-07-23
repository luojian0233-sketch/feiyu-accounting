package com.local.autobookkeeping.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.local.autobookkeeping.domain.model.CategoryTotal
import java.util.Locale
import kotlin.math.max

private val chartColors = listOf(
    Color(0xFF23866B),
    Color(0xFFFFA76C),
    Color(0xFF5C8FC9),
    Color(0xFFE06D83),
    Color(0xFF8B75BF),
    Color(0xFFE2B84B),
    Color(0xFF4E9B9B),
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

@Composable
fun PieChart(
    items: List<CategoryTotal>,
    modifier: Modifier = Modifier,
) {
    val sweeps = calculatePieSweeps(items.map { it.amountMinor })
    val total = items.sumOf { it.amountMinor }
    val description = items.joinToString("；") { item ->
        val percentage = item.amountMinor.toDouble() / total.toDouble() * 100.0
        "${item.category}，${formatUnsignedMoney(item.amountMinor)}元，" +
            String.format(Locale.CHINA, "%.1f%%", percentage)
    }
    Canvas(
        modifier
            .testTag("statistics-pie")
            .semantics { contentDescription = description },
    ) {
        var startAngle = -90f
        items.forEachIndexed { index, item ->
            val sweep = sweeps[index].toFloat()
            drawArc(
                color = categoryChartColor(item.category),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
            )
            startAngle += sweep
        }
    }
}

fun categoryChartColor(category: String): Color =
    chartColors[Math.floorMod(category.hashCode(), chartColors.size)]

internal fun formatUnsignedMoney(amountMinor: Long): String =
    "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
