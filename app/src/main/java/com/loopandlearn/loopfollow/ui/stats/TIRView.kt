package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.engine.stats.TIRDataPoint
import com.loopandlearn.loopfollow.engine.stats.TIRPeriod
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** Time in range per period of the day; tapping cycles the range mode. */
@Composable
internal fun TIRView(tirData: List<TIRDataPoint>, onToggleMode: () -> Unit, modifier: Modifier = Modifier) {
    observeDisplaySettings()
    val colors = LocalAppColors.current
    val average = tirData.firstOrNull { it.period == TIRPeriod.AVERAGE }

    StatsCard(modifier.clickableNoRipple(onToggleMode), interactive = true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(UnitSettingsStore.timeInRangeMode.title, fontSize = StatsFont.caption, color = colors.secondaryText)
                Spacer(Modifier.weight(1f))
                if (average != null) {
                    Text(formatRange(), fontSize = StatsFont.caption, color = colors.secondaryText)
                }
            }

            if (tirData.isNotEmpty()) {
                TIRGraph(
                    tirData,
                    Modifier
                        .padding(top = 10.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                        .fillMaxWidth()
                        .height(250.dp),
                )

                if (average != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cutoffs in ${UnitSettingsStore.glucoseUnit.raw}", fontSize = StatsFont.caption2, color = colors.secondaryText)
                        val t = displayBandThresholds()
                        TIRLegendItem(colors.orange, "Very High (> ${t.veryHigh})", average.veryHigh)
                        if (hasHighBand()) TIRLegendItem(colors.yellow, "High (>${t.high}–${t.veryHigh})", average.high)
                        TIRLegendItem(colors.green, "In Range (${t.low}–${t.high})", average.inRange)
                        if (hasLowBand()) TIRLegendItem(colors.red.opacity(0.5f), "Low (${t.veryLow}–<${t.low})", average.low)
                        TIRLegendItem(colors.red.opacity(0.8f), "Very Low (< ${t.veryLow})", average.veryLow)
                    }
                }
            } else {
                Box(Modifier.height(250.dp), contentAlignment = Alignment.Center) {
                    Text("No data available", fontSize = StatsFont.caption, color = colors.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun TIRLegendItem(color: Color, label: String, percentage: Double) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).background(color))
        Text(cFormat(percentage, 1) + "%", fontSize = StatsFont.caption2, color = colors.primaryText)
        Text(label, fontSize = StatsFont.caption2, color = colors.secondaryText)
    }
}

private class DisplayBandThresholds(val veryLow: String, val low: String, val high: String, val veryHigh: String)

private fun formatThreshold(mgdl: Double): String =
    cFormat(UnitSettingsStore.convertMgdlToDisplay(mgdl), UnitSettingsStore.glucoseUnit.fractionDigits)

private fun displayBandThresholds(): DisplayBandThresholds {
    val t = UnitSettingsStore.effectiveBandThresholds()
    return DisplayBandThresholds(formatThreshold(t.veryLow), formatThreshold(t.low), formatThreshold(t.high), formatThreshold(t.veryHigh))
}

/** A custom range that reaches past the very low cutoff leaves no low band. */
private fun hasLowBand(): Boolean = UnitSettingsStore.effectiveBandThresholds().let { it.veryLow < it.low }

/** A custom range that reaches past the very high cutoff leaves no high band. */
private fun hasHighBand(): Boolean = UnitSettingsStore.effectiveBandThresholds().let { it.high < it.veryHigh }

private fun formatRange(): String {
    val (low, high) = UnitSettingsStore.effectiveThresholds()
    return "${formatThreshold(low)} – ${formatThreshold(high)} ${UnitSettingsStore.glucoseUnit.raw}"
}

private fun bandColors(colors: AppColors): List<Color> = listOf(
    colors.red.opacity(0.8f),
    colors.red.opacity(0.5f),
    colors.green.opacity(0.7f),
    colors.yellow.opacity(0.7f),
    colors.orange.opacity(0.7f),
)

/** Stacked percentage bars, one per period, very low at the bottom. */
@Composable
private fun TIRGraph(tirData: List<TIRDataPoint>, modifier: Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = StatsFont.caption2, color = colors.secondaryText)
    val bands = bandColors(colors)

    Canvas(modifier.background(colors.background)) {
        val yMax = 102.0
        val ticks = listOf(0, 25, 50, 75, 100)
        val yLabels = ticks.map { measurer.measure("$it%", labelStyle) }
        val xLabels = tirData.map { measurer.measure(it.period.label, labelStyle) }
        val tickLength = 4.dp.toPx()
        val gap = 2.dp.toPx()

        val left = (yLabels.maxOf { it.size.width } + tickLength + gap).toFloat()
        val bottom = size.height - (xLabels.maxOfOrNull { it.size.height } ?: 0) - gap
        val top = 0f
        val right = size.width
        fun yFor(v: Double) = (bottom - (v / yMax) * (bottom - top)).toFloat()

        val grid = colors.secondary(0.5f)
        ticks.forEachIndexed { i, tick ->
            val y = yFor(tick.toDouble())
            drawLine(grid, Offset(left, y), Offset(right, y), 0.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
            drawLine(grid, Offset(left - tickLength, y), Offset(left, y), 0.5.dp.toPx())
            val layout = yLabels[i]
            drawText(layout, topLeft = Offset(left - tickLength - gap - layout.size.width, y - layout.size.height / 2f))
        }

        if (tirData.isEmpty()) return@Canvas
        val slot = (right - left) / tirData.size
        val barWidth = slot * 0.6f
        tirData.forEachIndexed { i, point ->
            val centerX = left + slot * (i + 0.5f)
            var cumulative = 0.0
            val values = listOf(point.veryLow, point.low, point.inRange, point.high, point.veryHigh)
            values.forEachIndexed { b, value ->
                if (value > 0) {
                    val yTop = yFor(cumulative + value)
                    val yBottom = yFor(cumulative)
                    drawRect(bands[b], Offset(centerX - barWidth / 2, yTop), Size(barWidth, yBottom - yTop))
                }
                cumulative += value
            }
            val layout = xLabels[i]
            drawText(layout, topLeft = Offset(centerX - layout.size.width / 2f, bottom + gap))
        }
    }
}
