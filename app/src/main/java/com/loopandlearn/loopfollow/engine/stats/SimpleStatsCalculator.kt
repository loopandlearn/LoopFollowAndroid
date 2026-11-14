package com.loopandlearn.loopfollow.engine.stats

import com.loopandlearn.loopfollow.engine.BasalProfileEntry
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import kotlin.math.sqrt

/** Glucose values in the display unit, insulin in U per day, carbs in g per day. */
data class SimpleStats(
    val avgGlucose: Double? = null,
    val stdDeviation: Double? = null,
    val coefficientOfVariation: Double? = null,
    val totalDailyDose: Double? = null,
    val programmedBasal: Double? = null,
    val actualBasal: Double? = null,
    val avgBolus: Double? = null,
    val avgCarbs: Double? = null,
    val totalNegativeBasal: Double? = null,
    val totalPositiveBasal: Double? = null,
)

class SimpleStatsCalculator(private val dataService: StatsDataService) {
    private data class ActualBasal(
        val totalBasal: Double,
        val daysWithData: Int,
        val totalPositiveAdjustment: Double,
        val totalNegativeAdjustment: Double,
    )

    /** The statistics for the selected range, or null when it has no BG readings. */
    fun calculate(): SimpleStats? {
        val bgData = dataService.getBGData()
        if (bgData.isEmpty()) return null

        val totalGlucose = bgData.sumOf { it.sgv }
        val avgBGmgdL = totalGlucose.toDouble() / bgData.size
        val avgGlucose = UnitSettingsStore.convertMgdlToDisplay(avgBGmgdL)

        val variance = bgData.sumOf { val diff = it.sgv - avgBGmgdL; diff * diff }
        val stdDevMgdL = sqrt(variance / bgData.size)
        val stdDeviation = UnitSettingsStore.convertMgdlToDisplay(stdDevMgdL)

        val coefficientOfVariation = if (avgBGmgdL > 0) stdDevMgdL / avgBGmgdL * 100.0 else null

        val bolusesInPeriod = dataService.getBolusData()
        val smbInPeriod = dataService.getSMBData()
        val totalBolusInPeriod = bolusesInPeriod.sumOf { it.value } + smbInPeriod.sumOf { it.value }

        val cutoffTime = dataService.startDate
        val allBolusDates = (bolusesInPeriod + smbInPeriod).map { it.date }.filter { it >= cutoffTime }
        val actualDays = calculateActualDaysCovered(allBolusDates, dataService.daysToAnalyze)

        val avgBolus = if (actualDays > 0) totalBolusInPeriod / actualDays else null

        val totalCarbsInPeriod = dataService.getCarbData().sumOf { it.value }
        val daysWithData = dataService.daysToAnalyze
        val avgCarbs = if (daysWithData > 0) totalCarbsInPeriod / daysWithData else null

        val basalProfile = dataService.getBasalProfile()
        val dailyProgrammedBasal = calculateProgrammedBasalFromProfile(basalProfile)

        val actual = calculateActualBasalFromTempBasals(dataService.getTempBasalData(), basalProfile, dailyProgrammedBasal)

        val totalPositiveBasal = if (actual.daysWithData > 0) actual.totalPositiveAdjustment / actual.daysWithData else null
        val totalNegativeBasal = if (actual.daysWithData > 0) actual.totalNegativeAdjustment / actual.daysWithData else null

        val avgDailyBolus = if (actualDays > 0) totalBolusInPeriod / actualDays else 0.0
        val avgDailyBasal = if (actual.daysWithData > 0) actual.totalBasal / actual.daysWithData else 0.0
        val actualBasal = if (actual.daysWithData > 0) avgDailyBasal else null

        val totalDailyDose = if (actualDays > 0 || actual.daysWithData > 0) avgDailyBolus + avgDailyBasal else null

        return SimpleStats(
            avgGlucose = avgGlucose,
            stdDeviation = stdDeviation,
            coefficientOfVariation = coefficientOfVariation,
            totalDailyDose = totalDailyDose,
            programmedBasal = dailyProgrammedBasal,
            actualBasal = actualBasal,
            avgBolus = avgBolus,
            avgCarbs = avgCarbs,
            totalNegativeBasal = totalNegativeBasal,
            totalPositiveBasal = totalPositiveBasal,
        )
    }

    /**
     * Actual basal = programmed basal + the sum of all temp basal adjustments,
     * where an adjustment is (temp rate - scheduled rate) × duration.
     */
    private fun calculateActualBasalFromTempBasals(
        tempBasals: List<TempBasalEntry>,
        basalProfile: List<BasalProfileEntry>,
        dailyProgrammedBasal: Double,
    ): ActualBasal {
        val cutoffTime = dataService.startDate
        val endTime = dataService.endDate

        val relevantTempBasals = tempBasals.filter {
            val tempEnd = it.startTime + it.durationMinutes * 60
            tempEnd > cutoffTime && it.startTime < endTime
        }

        if (relevantTempBasals.isEmpty()) {
            return ActualBasal(dailyProgrammedBasal, dataService.daysToAnalyze, 0.0, 0.0)
        }

        var totalAdjustment = 0.0
        var totalPositiveAdjustment = 0.0
        var totalNegativeAdjustment = 0.0

        for (tempBasal in relevantTempBasals) {
            val effectiveStart = maxOf(tempBasal.startTime, cutoffTime)
            val effectiveEnd = minOf(tempBasal.startTime + tempBasal.durationMinutes * 60, endTime)
            if (effectiveEnd <= effectiveStart) continue

            val adjustment = calculateAdjustmentForTempBasal(tempBasal.rate, effectiveStart, effectiveEnd, basalProfile)
            totalAdjustment += adjustment
            if (adjustment > 0) {
                totalPositiveAdjustment += adjustment
            } else if (adjustment < 0) {
                totalNegativeAdjustment += adjustment
            }
        }

        val uniqueDays = relevantTempBasals.map { StatsCalendar.localDate(it.startTime) }.toSet()
        val daysWithData = minOf(uniqueDays.size, dataService.daysToAnalyze)

        val totalActualBasal = dailyProgrammedBasal * daysWithData + totalAdjustment
        return ActualBasal(totalActualBasal, daysWithData, totalPositiveAdjustment, totalNegativeAdjustment)
    }

    /** (temp rate - scheduled rate) × hours, summed over the profile segments the temp basal spans. */
    private fun calculateAdjustmentForTempBasal(
        tempRate: Double,
        startTime: Double,
        endTime: Double,
        profile: List<BasalProfileEntry>,
    ): Double {
        if (profile.isEmpty() || endTime <= startTime) return 0.0

        var totalAdjustment = 0.0
        val sortedProfile = profile.sortedBy { it.timeAsSeconds }

        var currentTime = startTime
        while (currentTime < endTime) {
            val dayStart = StatsCalendar.startOfDay(currentTime)
            val nextDayStart = StatsCalendar.addDays(dayStart, 1)

            for (i in sortedProfile.indices) {
                val scheduledRate = sortedProfile[i].value
                val segmentStartInDay = dayStart + sortedProfile[i].timeAsSeconds
                val segmentEndInDay = if (i < sortedProfile.size - 1) dayStart + sortedProfile[i + 1].timeAsSeconds else nextDayStart

                val overlapStart = maxOf(currentTime, segmentStartInDay)
                val overlapEnd = minOf(endTime, segmentEndInDay)
                if (overlapEnd > overlapStart) {
                    val durationHours = (overlapEnd - overlapStart) / 3600.0
                    totalAdjustment += (tempRate - scheduledRate) * durationHours
                }
            }
            currentTime = nextDayStart
        }
        return totalAdjustment
    }

    private fun calculateActualDaysCovered(dates: List<Double>, requestedDays: Int): Int {
        if (dates.isEmpty()) return requestedDays
        val cutoffTime = dataService.startDate
        val uniqueDays = dates.filter { it >= cutoffTime }.map { StatsCalendar.localDate(it) }.toSet()
        return minOf(uniqueDays.size, requestedDays)
    }

    private fun calculateProgrammedBasalFromProfile(basalProfile: List<BasalProfileEntry>): Double {
        if (basalProfile.isEmpty()) return 0.0

        val sortedProfile = basalProfile.sortedBy { it.timeAsSeconds }
        val secondsInDay = 24 * 60 * 60.0
        var totalBasal = 0.0
        for (i in sortedProfile.indices) {
            val currentTime = sortedProfile[i].timeAsSeconds
            val nextTime = if (i < sortedProfile.size - 1) sortedProfile[i + 1].timeAsSeconds else secondsInDay
            totalBasal += sortedProfile[i].value * (nextTime - currentTime) / 3600.0
        }
        return totalBasal
    }
}
