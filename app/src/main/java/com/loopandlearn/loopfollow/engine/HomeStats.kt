package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.GlycemicMetricCalculator
import com.loopandlearn.loopfollow.helpers.GlycemicMetricMode
import com.loopandlearn.loopfollow.helpers.GlycemicOutputUnit
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.VariabilityMetricMode
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import kotlin.math.sqrt

/** printf-style fixed formatting: rounds the exact binary value, as "%.Nf" does. */
fun cFormat(value: Double, digits: Int): String =
    if (value.isNaN() || value.isInfinite()) value.toString() else BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

class StatsData(bgData: List<ShareGlucoseData>) {
    val percentLow: Float
    val percentRange: Float
    val percentHigh: Float
    val avgBG: Float
    val a1C: Float
    val stdDev: Float
    var coefficientOfVariation: Float = 0f

    init {
        val (low, high) = UnitSettingsStore.effectiveThresholds()
        var countLow = 0
        var countRange = 0
        var countHigh = 0
        var total = 0
        for (r in bgData) {
            when {
                r.sgv < low -> countLow++
                r.sgv > high -> countHigh++
                else -> countRange++
            }
            total += r.sgv
        }
        percentLow = countLow.toFloat() / bgData.size * 100
        percentRange = countRange.toFloat() / bgData.size * 100
        percentHigh = countHigh.toFloat() / bgData.size * 100

        val count = maxOf(bgData.size, 1)
        avgBG = (total / count).toFloat()

        var partialSum = 0f
        for (r in bgData) partialSum += (r.sgv - avgBG) * (r.sgv - avgBG)
        val stdDevMgdl = sqrt(partialSum / bgData.size)
        if (avgBG > 0) coefficientOfVariation = stdDevMgdl / avgBG * 100f
        stdDev = UnitSettingsStore.convertMgdlToDisplay(stdDevMgdl.toDouble()).toFloat()

        val avgDisplay = UnitSettingsStore.convertMgdlToDisplay(avgBG.toDouble())
        val metric = if (UnitSettingsStore.glycemicMetricMode == GlycemicMetricMode.GMI) {
            GlycemicMetricCalculator.calculateGMI(avgDisplay)
        } else {
            GlycemicMetricCalculator.calculateEhba1c(avgDisplay)
        }
        a1C = (metric ?: 0.0).toFloat()
    }
}

data class TIRPercentages(val veryLow: Double, val low: Double, val inRange: Double, val high: Double, val veryHigh: Double)

object TIRCalculator {
    fun calculatePercentages(readings: List<Double>, veryLow: Double, low: Double, high: Double, veryHigh: Double): TIRPercentages {
        val total = readings.size.toDouble()
        if (total <= 0) return TIRPercentages(0.0, 0.0, 0.0, 0.0, 0.0)
        var vl = 0
        var l = 0
        var r = 0
        var h = 0
        var vh = 0
        for (g in readings) {
            when {
                g < veryLow -> vl++
                g < low -> l++
                g > veryHigh -> vh++
                g > high -> h++
                else -> r++
            }
        }
        return TIRPercentages(vl / total * 100, l / total * 100, r / total * 100, h / total * 100, vh / total * 100)
    }
}

data class HomeStatsState(
    val lowPercent: String = "",
    val inRangePercent: String = "",
    val highPercent: String = "",
    val avgBG: String = "",
    val estA1C: String = "",
    val estA1CTitle: String = "Est A1C:",
    val stdDev: String = "",
    val stdDevTitle: String = "Std Dev:",
    val pieLow: Double = 0.0,
    val pieRange: Double = 0.0,
    val pieHigh: Double = 0.0,
    val bandTitle: String = "Time in Range",
    val bandPeriod: String = "last 24h",
    val bandHasData: Boolean = false,
    val band: TIRPercentages = TIRPercentages(0.0, 0.0, 0.0, 0.0, 0.0),
)

/** The home screen statistics box and Time in Range band. */
object StatsDisplayModel {
    private val state = MutableStateFlow(HomeStatsState())
    val flow: StateFlow<HomeStatsState> = state.asStateFlow()

    /** Start of the period: the last 24 hours, or since midnight in the display time zone. */
    private fun periodStart(): Double {
        if (Storage.statsSinceMidnight.value) {
            val cal = Calendar.getInstance(DateTimeUtils.displayTimeZone())
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis / 1000.0
        }
        return nowSeconds() - 24 * 3600
    }

    fun update(bgData: List<ShareGlucoseData>) {
        val start = periodStart()
        val periodData = bgData.filter { it.date >= start }
        var s = if (periodData.isEmpty()) {
            HomeStatsState(
                lowPercent = "--", inRangePercent = "--", highPercent = "--",
                avgBG = "--", estA1C = "--", stdDev = "--",
                estA1CTitle = state.value.estA1CTitle, stdDevTitle = state.value.stdDevTitle,
            )
        } else {
            val stats = StatsData(periodData)
            val a1cTitle = if (UnitSettingsStore.glycemicMetricMode == GlycemicMetricMode.GMI) "GMI:" else "Est. A1C:"
            val a1c = if (UnitSettingsStore.glycemicOutputUnit == GlycemicOutputUnit.MMOL_MOL) cFormat(stats.a1C.toDouble(), 0) else cFormat(stats.a1C.toDouble(), 1)
            val (sdTitle, sd) = if (UnitSettingsStore.variabilityMetricMode == VariabilityMetricMode.STD_DEVIATION) {
                "Std Dev:" to cFormat(stats.stdDev.toDouble(), if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MGDL) 0 else 1)
            } else {
                "CV:" to cFormat(stats.coefficientOfVariation.toDouble(), 1) + "%"
            }
            HomeStatsState(
                lowPercent = cFormat(stats.percentLow.toDouble(), 1) + "%",
                inRangePercent = cFormat(stats.percentRange.toDouble(), 1) + "%",
                highPercent = cFormat(stats.percentHigh.toDouble(), 1) + "%",
                avgBG = Localizer.toDisplayUnits(cFormat(stats.avgBG.toDouble(), 0)),
                estA1C = a1c,
                estA1CTitle = a1cTitle,
                stdDev = sd,
                stdDevTitle = sdTitle,
                pieLow = stats.percentLow.toDouble(),
                pieRange = stats.percentRange.toDouble(),
                pieHigh = stats.percentHigh.toDouble(),
            )
        }

        val t = UnitSettingsStore.effectiveBandThresholds()
        val values = bgData.filter { it.date >= start && it.sgv > 0 }.map { it.sgv.toDouble() }
        s = s.copy(
            bandTitle = UnitSettingsStore.timeInRangeMode.title,
            band = TIRCalculator.calculatePercentages(values, t.veryLow, t.low, t.high, t.veryHigh),
            bandHasData = values.isNotEmpty(),
            bandPeriod = if (Storage.statsSinceMidnight.value) "today" else "last 24h",
        )
        state.value = s
    }
}
