package com.loopandlearn.loopfollow.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.StatsDisplayModel
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import java.text.NumberFormat
import java.util.Locale

/** The home screen stats: the statistics box, or the Time in Range band. */
@Composable
fun StatsView(onTap: () -> Unit) {
    val showBand by Storage.showTIRBand.flow.collectAsStateWithLifecycle()
    if (showBand) TIRBand(onTap) else StatsBox(onTap)
}

private fun AppColors.secondaryBackground() = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

@Composable
private fun StatsBox(onTap: () -> Unit) {
    val colors = LocalAppColors.current
    val s by StatsDisplayModel.flow.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().height(100.dp).background(colors.secondaryBackground()).clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PieChart(s.pieLow, s.pieRange, s.pieHigh, Modifier.padding(start = 8.dp).size(80.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                StatColumn("Low:", s.lowPercent)
                StatColumn("In Range:", s.inRangePercent)
                StatColumn("High:", s.highPercent)
            }
            Row {
                StatColumn("Avg BG:", s.avgBG)
                StatColumn(s.estA1CTitle, s.estA1C)
                StatColumn(s.stdDevTitle, s.stdDev)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatColumn(title: String, value: String) {
    val colors = LocalAppColors.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 15.sp, color = colors.primaryText, maxLines = 1)
        Text(value, fontSize = 15.sp, color = colors.primaryText, maxLines = 1)
    }
}

@Composable
private fun PieChart(low: Double, range: Double, high: Double, modifier: Modifier) {
    val colors = LocalAppColors.current
    val slices = listOf(maxOf(low, 0.1) to colors.red, maxOf(range, 0.1) to colors.green, maxOf(high, 0.1) to colors.yellow)
    val total = slices.sumOf { it.first }
    Canvas(modifier) {
        var start = -90f
        for ((value, color) in slices) {
            val sweep = (value / total * 360).toFloat()
            drawArc(color, start, sweep, useCenter = true)
            start += sweep
        }
    }
}

@Composable
private fun TIRBand(onTap: () -> Unit) {
    val colors = LocalAppColors.current
    val s by StatsDisplayModel.flow.collectAsStateWithLifecycle()
    val percent = if (s.bandHasData) {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }.format(s.band.inRange) + " %"
    } else {
        "-- %"
    }
    val segments = if (s.bandHasData) {
        listOf(
            colors.red.copy(alpha = 0.8f) to s.band.veryLow,
            colors.red.copy(alpha = 0.5f) to s.band.low,
            colors.green.copy(alpha = 0.7f) to s.band.inRange,
            colors.yellow.copy(alpha = 0.7f) to s.band.high,
            colors.orange.copy(alpha = 0.7f) to s.band.veryHigh,
        ).map { it.first to (it.second / 100).toFloat() }
    } else {
        listOf(colors.secondaryText.copy(alpha = 0.3f) to 1f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(colors.secondaryBackground(), RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percent, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
                Spacer(Modifier.width(6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.bandTitle) }
                        append(" " + s.bandPeriod)
                    },
                    fontSize = 15.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            DistributionBar(segments, Modifier.fillMaxWidth().height(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondaryText)
    }
}

/** Capsules sized by share; every non-empty segment is at least 4 dp wide. */
@Composable
private fun DistributionBar(segments: List<Pair<Color, Float>>, modifier: Modifier) {
    Canvas(modifier) {
        val spacing = 2.dp.toPx()
        val minWidth = 4.dp.toPx()
        val shown = segments.filter { it.second > 0 }
        val available = maxOf(size.width - spacing * maxOf(shown.size - 1, 0), 0f)
        val widths = shown.map { maxOf(it.second * available, minWidth) }.toMutableList()
        val excess = widths.sum() - available
        if (excess > 0 && widths.isNotEmpty()) {
            val widest = widths.indices.maxBy { widths[it] }
            widths[widest] = maxOf(widths[widest] - excess, minWidth)
        }
        var x = 0f
        shown.forEachIndexed { i, (color, _) ->
            drawRoundRect(color, Offset(x, 0f), Size(widths[i], size.height), CornerRadius(size.height / 2))
            x += widths[i] + spacing
        }
    }
}
