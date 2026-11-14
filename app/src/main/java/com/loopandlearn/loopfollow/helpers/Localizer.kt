package com.loopandlearn.loopfollow.helpers

import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.GlucoseThresholds
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.TimeInRangeDisplayMode
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

enum class GlycemicMetricMode(val raw: String) { EHBA1C("eHbA1c"), GMI("GMI") }

enum class GlycemicOutputUnit(val raw: String) { PERCENT("%"), MMOL_MOL("mmol/mol") }

enum class VariabilityMetricMode(val raw: String) { STD_DEVIATION("Std Deviation"), CV("CV") }

object UnitSettingsStore {
    var glucoseUnit: GlucoseDisplayUnit
        get() = GlucoseDisplayUnit.fromRaw(Storage.units.value)
        set(value) {
            Storage.units.value = value.raw
        }

    var timeInRangeMode: TimeInRangeDisplayMode
        get() = TimeInRangeDisplayMode.fromRaw(Storage.timeInRangeModeRaw.value)
        set(value) {
            Storage.timeInRangeModeRaw.value = value.raw
        }

    /** The effective low/high thresholds (in mg/dL) for the current range mode. */
    fun effectiveThresholds(): Pair<Double, Double> =
        timeInRangeMode.fixedThresholds ?: (Storage.lowLine.value to Storage.highLine.value)

    data class BandThresholds(val veryLow: Double, val low: Double, val high: Double, val veryHigh: Double)

    fun effectiveBandThresholds(): BandThresholds {
        val (low, high) = effectiveThresholds()
        return BandThresholds(
            minOf(GlucoseThresholds.VERY_LOW, low),
            low,
            high,
            maxOf(GlucoseThresholds.VERY_HIGH, high),
        )
    }

    var glycemicMetricMode: GlycemicMetricMode
        get() = if (Storage.showGMI.value) GlycemicMetricMode.GMI else GlycemicMetricMode.EHBA1C
        set(value) {
            Storage.showGMI.value = value == GlycemicMetricMode.GMI
        }

    var glycemicOutputUnit: GlycemicOutputUnit
        get() = if (Storage.useIFCC.value) GlycemicOutputUnit.MMOL_MOL else GlycemicOutputUnit.PERCENT
        set(value) {
            Storage.useIFCC.value = value == GlycemicOutputUnit.MMOL_MOL
        }

    var variabilityMetricMode: VariabilityMetricMode
        get() = if (Storage.showStdDev.value) VariabilityMetricMode.STD_DEVIATION else VariabilityMetricMode.CV
        set(value) {
            Storage.showStdDev.value = value == VariabilityMetricMode.STD_DEVIATION
        }

    fun convertMgdlToDisplay(mgdl: Double): Double =
        if (glucoseUnit == GlucoseDisplayUnit.MMOLL) mgdl * GlucoseConversion.MGDL_TO_MMOL else mgdl

    fun convertDisplayToMgdl(value: Double): Double =
        if (glucoseUnit == GlucoseDisplayUnit.MMOLL) value * GlucoseConversion.MMOL_TO_MGDL else value
}

object GlycemicMetricCalculator {
    fun calculateEhba1c(avgGlucoseInDisplayUnits: Double?): Double? {
        avgGlucoseInDisplayUnits ?: return null
        return when (UnitSettingsStore.glycemicOutputUnit) {
            GlycemicOutputUnit.MMOL_MOL -> {
                val avgMmolL = if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MMOLL) {
                    avgGlucoseInDisplayUnits
                } else {
                    avgGlucoseInDisplayUnits * GlucoseConversion.MGDL_TO_MMOL
                }
                avgMmolL * 6.936514699616532 - 6.628248828291433
            }
            GlycemicOutputUnit.PERCENT -> {
                val avgMgdL = UnitSettingsStore.convertDisplayToMgdl(avgGlucoseInDisplayUnits)
                (avgMgdL + 46.7) / 28.7
            }
        }
    }

    fun calculateGMI(avgGlucoseInDisplayUnits: Double?): Double? {
        avgGlucoseInDisplayUnits ?: return null
        return when (UnitSettingsStore.glycemicOutputUnit) {
            GlycemicOutputUnit.PERCENT -> {
                val avgMgdL = UnitSettingsStore.convertDisplayToMgdl(avgGlucoseInDisplayUnits)
                3.31 + (0.02392 * avgMgdL)
            }
            GlycemicOutputUnit.MMOL_MOL -> {
                val avgMmolL = if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MMOLL) {
                    avgGlucoseInDisplayUnits
                } else {
                    avgGlucoseInDisplayUnits * GlucoseConversion.MGDL_TO_MMOL
                }
                12.71 + (4.70587 * avgMmolL)
            }
        }
    }
}

object Localizer {
    fun formatToLocalizedString(value: Double, maxFractionDigits: Int = 1, minFractionDigits: Int = 0): String {
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        formatter.maximumFractionDigits = maxFractionDigits
        formatter.minimumFractionDigits = minFractionDigits
        return formatter.format(value)
    }

    /** Formats a glucose value given in mg/dL in the preferred unit. */
    fun formatQuantity(mgdl: Double): String {
        val unit = UnitSettingsStore.glucoseUnit
        val value = UnitSettingsStore.convertMgdlToDisplay(mgdl)
        return formatToLocalizedString(value, unit.fractionDigits, unit.fractionDigits)
    }

    fun formatTimestampToLocalString(timestamp: Double): String {
        val formatter = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
        return formatter.format(Date((timestamp * 1000).toLong()))
    }

    fun formatLocalDouble(value: Double, unit: String? = null): String {
        val units = GlucoseDisplayUnit.fromRaw(unit ?: UnitSettingsStore.glucoseUnit.raw)
        return formatToLocalizedString(value, units.fractionDigits, units.fractionDigits)
    }

    /** Converts a mg/dL value (as text) to the display unit. */
    fun toDisplayUnits(value: String): String {
        val number = value.toFloatOrNull() ?: return value
        val units = UnitSettingsStore.glucoseUnit
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        formatter.maximumFractionDigits = units.fractionDigits
        formatter.minimumFractionDigits = units.fractionDigits
        return if (units == GlucoseDisplayUnit.MGDL) {
            formatter.format(number.toDouble())
        } else {
            formatter.format(number.toDouble() * GlucoseConversion.MGDL_TO_MMOL)
        }
    }

    fun toDisplayUnits(value: Int): String = toDisplayUnits(value.toString())

    fun toDisplayUnits(value: Double): String = toDisplayUnits(value.toString())

    fun removePeriodAndCommaForBadge(value: String): String = value.replace(".", "").replace(",", "")
}

/** Insulin amounts with the precision of the pump's bolus increment. */
object InsulinFormatter {
    val fractionDigits: Int
        get() {
            val step = maxOf(0.001, Storage.bolusIncrement.value)
            if (step >= 1) return 0
            var v = step
            var d = 0
            while (d < 6 && kotlin.math.abs(kotlin.math.round(v) - v) > 1e-10) {
                v *= 10
                d += 1
            }
            return d.coerceIn(0, 5)
        }

    fun string(units: Double): String {
        val fd = fractionDigits
        return Localizer.formatToLocalizedString(units, fd, fd)
    }
}
