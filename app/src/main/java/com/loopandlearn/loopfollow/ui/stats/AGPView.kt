package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.loopandlearn.loopfollow.engine.stats.AGPDataPoint
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlin.math.abs

/** The ambulatory glucose profile: hourly percentiles over the selected days. */
@Composable
internal fun AGPView(agpData: List<AGPDataPoint>, modifier: Modifier = Modifier) {
    if (agpData.isEmpty()) return
    observeDisplaySettings()
    val colors = LocalAppColors.current

    StatsCard(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ambulatory Glucose Profile (AGP)", fontSize = StatsFont.caption, color = colors.secondaryText)

            AGPGraph(
                agpData,
                Modifier
                    .padding(top = 10.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .height(200.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(colors.gray.opacity(0.6f), "5th-95th")
                LegendItem(colors.blue.opacity(0.7f), "25th-75th")
                LegendItem(colors.blue, "Median")
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color))
        Text(label, fontSize = StatsFont.caption2, color = LocalAppColors.current.secondaryText)
    }
}

private class PercentileLine(val color: Color, val widthDp: Float, val value: (AGPDataPoint) -> Double)

@Composable
private fun AGPGraph(agpData: List<AGPDataPoint>, modifier: Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = StatsFont.caption2, color = colors.secondaryText)
    val annotationStyle = TextStyle(fontSize = StatsFont.caption2, color = colors.primaryText)

    val sortedData = agpData.sortedBy { it.timeOfDay }
    val lines = listOf(
        PercentileLine(colors.gray.opacity(0.6f), 1.5f) { it.p5 },
        PercentileLine(colors.blue.opacity(0.7f), 1.5f) { it.p25 },
        PercentileLine(colors.blue, 3f) { it.p50 },
        PercentileLine(colors.blue.opacity(0.7f), 1.5f) { it.p75 },
        PercentileLine(colors.gray.opacity(0.6f), 1.5f) { it.p95 },
    )
    val (low, high) = UnitSettingsStore.effectiveThresholds()
    val values = sortedData.flatMap { dp -> lines.map { it.value(dp) } }
    val plottedMin = minOf(values.minOrNull() ?: low, low)
    val plottedMax = maxOf(values.maxOrNull() ?: high, high)
    val span = maxOf(plottedMax - plottedMin, 1.0)
    val yMin = plottedMin
    val yMax = plottedMax + maxOf(span * 0.05, 1.0)
    val yAxisValues = if (abs(plottedMax - plottedMin) < 0.0001) listOf(plottedMin) else listOf(plottedMin, plottedMax)

    Canvas(modifier.background(colors.background)) {
        val tickLength = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val hours = (0..24 step 3).toList()
        val xLabels = hours.map { measurer.measure("$it", labelStyle) }
        val yLabels = yAxisValues.map { measurer.measure(Localizer.toDisplayUnits(it.toString()), labelStyle) }
        val lowLabel = measurer.measure(Localizer.toDisplayUnits(low.toString()), annotationStyle)
        val highLabel = measurer.measure(Localizer.toDisplayUnits(high.toString()), annotationStyle)

        val left = (yLabels + lowLabel + highLabel).maxOf { it.size.width } + gap * 2
        val right = size.width
        val top = 0f
        val bottom = size.height - xLabels.maxOf { it.size.height } - tickLength - gap
        fun xFor(hour: Double) = (left + hour / 24.0 * (right - left)).toFloat()
        fun yFor(v: Double) = (bottom - (v - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

        val grid = colors.primaryText.opacity(0.3f)
        hours.forEachIndexed { i, hour ->
            val x = xFor(hour.toDouble())
            drawLine(grid, Offset(x, top), Offset(x, bottom), 0.5.dp.toPx())
            drawLine(grid, Offset(x, bottom), Offset(x, bottom + tickLength), 0.5.dp.toPx())
            val layout = xLabels[i]
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, bottom + tickLength + gap))
        }

        yAxisValues.forEachIndexed { i, v ->
            val layout = yLabels[i]
            drawText(layout, topLeft = Offset(left - gap - layout.size.width, yFor(v) - layout.size.height / 2f))
        }

        val ruleColor = colors.secondaryText
        for ((threshold, label) in listOf(low to lowLabel, high to highLabel)) {
            val y = yFor(threshold)
            drawLine(ruleColor, Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            drawText(label, topLeft = Offset(left - gap - label.size.width, y - label.size.height / 2f))
        }

        for (line in lines) {
            val path = Path()
            sortedData.forEachIndexed { i, dp ->
                val x = xFor(dp.timeOfDay / 60.0)
                val y = yFor(line.value(dp))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line.color, style = Stroke(line.widthDp.dp.toPx()))
        }
    }
}
