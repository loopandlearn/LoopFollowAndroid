package com.loopandlearn.loopfollow.engine.stats

import com.loopandlearn.loopfollow.engine.ShareGlucoseData
import com.loopandlearn.loopfollow.engine.TIRCalculator
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.storage.GlucoseThresholds
import kotlin.math.floor

// MARK: - Data availability

enum class DataQuality(val description: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
}

data class DataAvailabilityInfo(
    val totalExpectedReadings: Int,
    val actualReadings: Int,
    val coveragePercentage: Double,
    val missingIntervals: Int,
    val dataQuality: DataQuality,
)

object DataAvailabilityCalculator {
    /** Coverage of the period by CGM readings, expecting one every 5 minutes. */
    fun calculateAvailability(bgData: List<ShareGlucoseData>, startDate: Double, endDate: Double): DataAvailabilityInfo {
        val intervalMinutes = 5.0
        val totalMinutes = (endDate - startDate) / 60.0
        val expectedReadings = (totalMinutes / intervalMinutes).toInt()

        val relevantData = bgData.filter { it.date >= startDate && it.date <= endDate }.sortedBy { it.date }

        if (relevantData.isEmpty()) {
            return DataAvailabilityInfo(
                totalExpectedReadings = maxOf(expectedReadings, 0),
                actualReadings = 0,
                coveragePercentage = 0.0,
                missingIntervals = maxOf(expectedReadings, 0),
                dataQuality = DataQuality.POOR,
            )
        }

        val coveredIntervals = HashSet<Int>()
        for (reading in relevantData) {
            val minutesSinceStart = (reading.date - startDate) / 60.0
            val intervalIndex = (minutesSinceStart / intervalMinutes).toInt()
            if (intervalIndex in 0 until expectedReadings) coveredIntervals.add(intervalIndex)
        }

        val actualCoveredIntervals = coveredIntervals.size
        val coveragePercentage = if (expectedReadings > 0) actualCoveredIntervals.toDouble() / expectedReadings * 100.0 else 0.0

        val quality = when {
            coveragePercentage >= 95.0 -> DataQuality.EXCELLENT
            coveragePercentage >= 70.0 -> DataQuality.GOOD
            coveragePercentage >= 50.0 -> DataQuality.FAIR
            else -> DataQuality.POOR
        }

        return DataAvailabilityInfo(
            totalExpectedReadings = expectedReadings,
            actualReadings = actualCoveredIntervals,
            coveragePercentage = coveragePercentage,
            missingIntervals = expectedReadings - actualCoveredIntervals,
            dataQuality = quality,
        )
    }
}

// MARK: - Time in range by period of day

enum class TIRPeriod(val label: String, val hourRange: IntRange?) {
    NIGHT("Night", 0 until 6),
    MORNING("Morning", 6 until 12),
    DAY("Day", 12 until 18),
    EVENING("Evening", 18 until 24),
    AVERAGE("Average", null),
}

data class TIRDataPoint(
    val period: TIRPeriod,
    val veryLow: Double,
    val low: Double,
    val inRange: Double,
    val high: Double,
    val veryHigh: Double,
)

object PeriodTIRCalculator {
    fun calculate(bgData: List<ShareGlucoseData>): List<TIRDataPoint> {
        if (bgData.isEmpty()) return emptyList()

        val t = UnitSettingsStore.effectiveBandThresholds()
        val periods = listOf(TIRPeriod.NIGHT, TIRPeriod.MORNING, TIRPeriod.DAY, TIRPeriod.EVENING)
        val periodData = mutableMapOf<TIRPeriod, MutableList<Double>>()

        for (reading in bgData) {
            val hour = StatsCalendar.hour(reading.date)
            val period = periods.firstOrNull { hour in it.hourRange!! } ?: continue
            periodData.getOrPut(period) { mutableListOf() }.add(reading.sgv.toDouble())
        }

        fun point(period: TIRPeriod, readings: List<Double>): TIRDataPoint {
            val p = TIRCalculator.calculatePercentages(readings, t.veryLow, t.low, t.high, t.veryHigh)
            return TIRDataPoint(period, p.veryLow, p.low, p.inRange, p.high, p.veryHigh)
        }

        val points = periods.map { period -> point(period, periodData[period].orEmpty()) }
        return points + point(TIRPeriod.AVERAGE, bgData.map { it.sgv.toDouble() })
    }
}

// MARK: - Ambulatory glucose profile

/** Hourly glucose percentiles in mg/dL; [timeOfDay] is minutes since midnight. */
data class AGPDataPoint(
    val timeOfDay: Int,
    val p5: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
)

object AGPCalculator {
    fun calculate(bgData: List<ShareGlucoseData>): List<AGPDataPoint> {
        if (bgData.isEmpty()) return emptyList()

        val hourData = mutableMapOf<Int, MutableList<Double>>()
        for (reading in bgData) {
            hourData.getOrPut(StatsCalendar.hour(reading.date)) { mutableListOf() }.add(reading.sgv.toDouble())
        }

        return (0 until 24).mapNotNull { hour ->
            val values = hourData[hour]
            if (values.isNullOrEmpty()) return@mapNotNull null
            val sorted = values.sorted()
            AGPDataPoint(
                timeOfDay = hour * 60,
                p5 = PercentileCalculator.percentile(sorted, 0.05),
                p25 = PercentileCalculator.percentile(sorted, 0.25),
                p50 = PercentileCalculator.percentile(sorted, 0.50),
                p75 = PercentileCalculator.percentile(sorted, 0.75),
                p95 = PercentileCalculator.percentile(sorted, 0.95),
            )
        }
    }
}

object PercentileCalculator {
    /** Linear interpolation between the closest ranks of [sorted]. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]

        val index = p * (sorted.size - 1)
        val lower = floor(index).toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = index - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }
}

// MARK: - Glycemia risk index

data class GRICalculationResult(val gri: Double, val hypoComponent: Double, val hyperComponent: Double)

object GRICalculator {
    /** GRI = (3.0 × hypo component) + (1.6 × hyper component), capped at 100, using the standard TIR cut-offs. */
    fun calculate(bgData: List<ShareGlucoseData>): GRICalculationResult {
        if (bgData.isEmpty()) return GRICalculationResult(0.0, 0.0, 0.0)

        var vLowCount = 0
        var lowCount = 0
        var highCount = 0
        var vHighCount = 0

        for (reading in bgData) {
            val glucose = reading.sgv.toDouble()
            when {
                glucose < GlucoseThresholds.VERY_LOW -> vLowCount++
                glucose < GlucoseThresholds.TIR_LOW -> lowCount++
                glucose > GlucoseThresholds.VERY_HIGH -> vHighCount++
                glucose > GlucoseThresholds.TIR_HIGH -> highCount++
            }
        }

        val totalCount = bgData.size.toDouble()
        val vLowPercent = vLowCount / totalCount * 100.0
        val lowPercent = lowCount / totalCount * 100.0
        val highPercent = highCount / totalCount * 100.0
        val vHighPercent = vHighCount / totalCount * 100.0

        val hypoComponent = vLowPercent + 0.8 * lowPercent
        val hyperComponent = vHighPercent + 0.5 * highPercent
        val gri = 3.0 * hypoComponent + 1.6 * hyperComponent
        return GRICalculationResult(minOf(gri, 100.0), hypoComponent, hyperComponent)
    }
}
