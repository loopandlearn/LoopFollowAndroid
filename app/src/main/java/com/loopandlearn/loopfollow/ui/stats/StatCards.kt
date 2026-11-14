package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.engine.stats.SimpleStats
import com.loopandlearn.loopfollow.helpers.GlycemicMetricCalculator
import com.loopandlearn.loopfollow.helpers.GlycemicOutputUnit
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

@Composable
private fun StatCard(
    title: String,
    value: String,
    unit: String?,
    color: Color,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    val colors = LocalAppColors.current
    StatsCard(modifier, interactive) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = StatsFont.caption, color = colors.secondaryText)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = StatsFont.title2, fontWeight = FontWeight.SemiBold, color = color)) { append(value) }
                    if (unit != null) {
                        withStyle(SpanStyle(fontSize = StatsFont.caption, color = colors.secondaryText)) { append(" $unit") }
                    }
                },
            )
        }
    }
}

private class CardSpec(
    val title: String,
    val value: String,
    val unit: String?,
    val color: Color,
    val interactive: Boolean = false,
    val modifier: Modifier = Modifier,
)

@Composable
internal fun StatsGrid(
    stats: SimpleStats,
    showGMI: Boolean,
    showStdDev: Boolean,
    onToggleGMI: () -> Unit,
    onToggleStdDev: () -> Unit,
) {
    observeDisplaySettings()
    val colors = LocalAppColors.current
    var showInsulinBreakdown by remember { mutableStateOf(false) }

    val hasInsulinData = stats.totalDailyDose != null || stats.avgBolus != null || stats.actualBasal != null || stats.programmedBasal != null
    val hasCarbData = stats.avgCarbs != null
    val glucoseUnit = UnitSettingsStore.glucoseUnit.raw
    val revealBreakdown = Modifier.tripleTap { showInsulinBreakdown = true }

    val cards = buildList {
        add(
            CardSpec(
                title = if (showGMI) "GMI" else "Est. A1C",
                value = if (showGMI) formatGMI(stats.avgGlucose) else formatEhbA1c(stats.avgGlucose),
                unit = UnitSettingsStore.glycemicOutputUnit.raw,
                color = colors.blue,
                interactive = true,
                modifier = Modifier.clickableNoRipple(onToggleGMI),
            ),
        )
        add(CardSpec("Avg Glucose", formatGlucose(stats.avgGlucose), glucoseUnit, Color(0.20f, 0.99f, 0.70f)))
        add(
            CardSpec(
                title = if (showStdDev) "Std Deviation" else "CV",
                value = if (showStdDev) formatGlucose(stats.stdDeviation) else formatOptional(stats.coefficientOfVariation, 1),
                unit = if (showStdDev) glucoseUnit else "%",
                color = colors.orange,
                interactive = true,
                modifier = Modifier.clickableNoRipple(onToggleStdDev),
            ),
        )
        if (hasCarbData) add(CardSpec("Avg Carbs", formatOptional(stats.avgCarbs, 0), "g/day", colors.yellow))
        if (hasInsulinData) {
            add(CardSpec("Programmed Basal", formatOptional(stats.programmedBasal, 2), "U/day", colors.indigo, modifier = revealBreakdown))
            add(CardSpec("Avg Bolus", formatOptional(stats.avgBolus, 2), "U/day", colors.purple, modifier = revealBreakdown))
            add(CardSpec("Total Daily Dose", formatOptional(stats.totalDailyDose, 2), "U", colors.pink, modifier = revealBreakdown))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (row in cards.chunked(2)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (card in row) {
                        StatCard(
                            card.title, card.value, card.unit, card.color,
                            modifier = card.modifier.weight(1f).fillMaxHeight(),
                            interactive = card.interactive,
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (hasInsulinData && showInsulinBreakdown) InsulinTotalsCard(stats)
    }
}

@Composable
private fun InsulinTotalsCard(stats: SimpleStats) {
    val colors = LocalAppColors.current
    StatsCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Insulin Totals", fontSize = StatsFont.caption, color = colors.secondaryText)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricRow("Programmed Basal", stats.programmedBasal, colors.indigo)
                MetricRow("Total Negative Basal", stats.totalNegativeBasal, colors.red)
                MetricRow("Total Positive Basal", stats.totalPositiveBasal, colors.green)
                MetricRow("Actual Basal", stats.actualBasal, colors.indigo)
                MetricRow("Avg Bolus", stats.avgBolus, colors.purple)
                MetricRow("Total Daily Dose", stats.totalDailyDose, colors.pink)
            }
        }
    }
}

@Composable
private fun MetricRow(title: String, value: Double?, color: Color) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = StatsFont.subheadline, color = colors.secondaryText, modifier = Modifier.weight(1f))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = StatsFont.headline, fontWeight = FontWeight.SemiBold, color = color)) {
                    append(formatOptional(value, 2))
                }
                withStyle(SpanStyle(fontSize = StatsFont.caption, color = colors.secondaryText)) { append(" U") }
            },
        )
    }
}

private fun formatOptional(value: Double?, digits: Int): String = value?.let { cFormat(it, digits) } ?: "---"

private fun formatGlycemic(value: Double?): String {
    value ?: return "---"
    return cFormat(value, if (UnitSettingsStore.glycemicOutputUnit == GlycemicOutputUnit.MMOL_MOL) 0 else 1)
}

private fun formatGMI(avgGlucose: Double?): String = formatGlycemic(GlycemicMetricCalculator.calculateGMI(avgGlucose))

private fun formatEhbA1c(avgGlucose: Double?): String = formatGlycemic(GlycemicMetricCalculator.calculateEhba1c(avgGlucose))

private fun formatGlucose(value: Double?): String =
    formatOptional(value, if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MGDL) 0 else 1)
