package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.engine.stats.GRICalculationResult
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlin.math.sqrt

private enum class GRIZone { A, B, C, D, E }

private fun zoneFor(gri: Double): GRIZone = when {
    gri <= 20 -> GRIZone.A
    gri <= 40 -> GRIZone.B
    gri <= 60 -> GRIZone.C
    gri <= 80 -> GRIZone.D
    else -> GRIZone.E
}

private fun griColor(zone: GRIZone, colors: AppColors): Color = when (zone) {
    GRIZone.A -> colors.green
    GRIZone.B -> colors.yellow
    GRIZone.C -> colors.orange
    GRIZone.D -> colors.red
    GRIZone.E -> colors.red.opacity(0.8f)
}

private fun gridZoneColor(zone: GRIZone, colors: AppColors): Color = when (zone) {
    GRIZone.A -> colors.green.opacity(0.3f)
    GRIZone.B -> colors.yellow.opacity(0.3f)
    GRIZone.C -> colors.orange.opacity(0.3f)
    GRIZone.D -> colors.red.opacity(0.3f)
    GRIZone.E -> colors.red.opacity(0.5f)
}

/** Hypo/hyper component pairs every 0.5 % whose GRI is at most 100, grouped by zone. */
private val griGrid: Map<GRIZone, List<Pair<Double, Double>>> = buildList {
    for (i in 0..60) {
        for (j in 0..120) {
            val hypo = i * 0.5
            val hyper = j * 0.5
            val gri = 3.0 * hypo + 1.6 * hyper
            if (gri <= 100) add(Triple(hypo, hyper, zoneFor(gri)))
        }
    }
}.groupBy({ it.third }, { it.first to it.second })

private const val Y_AXIS_LABEL_INSET = 24

/** The glycemia risk index with its position on the risk grid. */
@Composable
internal fun GRIView(result: GRICalculationResult?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    StatsCard(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("GRI (Glucose Risk Index)", fontSize = StatsFont.caption, color = colors.secondaryText)
                Spacer(Modifier.weight(1f))
                if (result != null) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val zone = zoneFor(result.gri)
                        Text(cFormat(result.gri, 0), fontSize = StatsFont.title2, fontWeight = FontWeight.SemiBold, color = griColor(zone, colors))
                        Text("Zone ${zone.name}", fontSize = StatsFont.caption2, color = colors.secondaryText)
                    }
                } else {
                    Text("---", fontSize = StatsFont.title2, fontWeight = FontWeight.SemiBold, color = colors.purple)
                }
            }

            if (result != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(contentAlignment = Alignment.CenterStart) {
                        GRIRiskGrid(
                            result.hypoComponent,
                            result.hyperComponent,
                            Modifier
                                .padding(top = 10.dp, start = Y_AXIS_LABEL_INSET.dp, end = 8.dp, bottom = 4.dp)
                                .fillMaxWidth()
                                .height(250.dp),
                        )
                        Box(Modifier.width(Y_AXIS_LABEL_INSET.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Hyperglycemia Component (%)",
                                fontSize = StatsFont.caption2,
                                color = colors.secondaryText,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.wrapContentSize(unbounded = true).rotate(-90f),
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width(Y_AXIS_LABEL_INSET.dp))
                        Text(
                            "Hypoglycemia Component (%)",
                            fontSize = StatsFont.caption2,
                            color = colors.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ZoneLegend()
            } else {
                Box(Modifier.height(250.dp), contentAlignment = Alignment.Center) {
                    Text("No data available", fontSize = StatsFont.caption, color = colors.secondaryText)
                }
            }
        }
    }
}

/** Zone legend in as many columns of at least 90 dp as fit. */
@Composable
private fun ZoneLegend() {
    val colors = LocalAppColors.current
    val items = listOf(
        colors.green to "A (0-20)",
        colors.yellow to "B (21-40)",
        colors.orange to "C (41-60)",
        colors.red to "D (61-80)",
        colors.red.opacity(0.8f) to "E (81-100)",
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = 12.dp
        val columns = maxOf(1, ((maxWidth + spacing) / (90.dp + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in items.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for ((color, label) in row) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).background(color.opacity(0.3f)))
                            Text(label, fontSize = StatsFont.caption2, color = colors.secondaryText, maxLines = 1, softWrap = false)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GRIRiskGrid(hypoComponent: Double, hyperComponent: Double, modifier: Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = StatsFont.caption2, color = colors.secondaryText)

    Canvas(modifier.background(colors.background)) {
        val xMax = 30.0
        val yMax = 61.0
        val xTicks = (0..30 step 5).toList()
        val yTicks = (0..60 step 10).toList()
        val tickLength = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val xLabels = xTicks.map { measurer.measure(cFormat(it.toDouble(), 0), labelStyle) }
        val yLabels = yTicks.map { measurer.measure(cFormat(it.toDouble(), 0), labelStyle) }

        val left = yLabels.maxOf { it.size.width } + tickLength + gap
        val right = size.width
        val top = 0f
        val bottom = size.height - xLabels.maxOf { it.size.height } - tickLength - gap
        fun xFor(v: Double) = (left + v / xMax * (right - left)).toFloat()
        fun yFor(v: Double) = (bottom - v / yMax * (bottom - top)).toFloat()

        val axis = colors.secondaryText
        xTicks.forEachIndexed { i, v ->
            val x = xFor(v.toDouble())
            drawLine(axis, Offset(x, bottom), Offset(x, bottom + tickLength), 0.5.dp.toPx())
            val layout = xLabels[i]
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, bottom + tickLength + gap))
        }
        yTicks.forEachIndexed { i, v ->
            val y = yFor(v.toDouble())
            drawLine(axis, Offset(left - tickLength, y), Offset(left, y), 0.5.dp.toPx())
            val layout = yLabels[i]
            drawText(layout, topLeft = Offset(left - tickLength - gap - layout.size.width, y - layout.size.height / 2f))
        }

        // Point mark sizes are areas in points²: 16 for the grid, 120 for the current value.
        val gridDiameter = sqrt(16f).dp.toPx()
        for ((zone, points) in griGrid) {
            drawPoints(
                points.map { (hypo, hyper) -> Offset(xFor(hypo), yFor(hyper)) },
                PointMode.Points,
                gridZoneColor(zone, colors),
                strokeWidth = gridDiameter,
                cap = StrokeCap.Round,
            )
        }

        drawCircle(colors.primaryText, radius = sqrt(120f).dp.toPx() / 2, center = Offset(xFor(hypoComponent), yFor(hyperComponent)))
    }
}
