package com.loopandlearn.loopfollow.engine.stats

import com.loopandlearn.loopfollow.engine.BasalGraphPoint
import com.loopandlearn.loopfollow.engine.BasalProfileEntry
import com.loopandlearn.loopfollow.engine.BolusGraphPoint
import com.loopandlearn.loopfollow.engine.CarbGraphPoint
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.ShareGlucoseData
import com.loopandlearn.loopfollow.helpers.nowSeconds

/** A temp basal with its absolute rate (U/hr), start (epoch s) and duration in minutes. */
data class TempBasalEntry(val rate: Double, val startTime: Double, val durationMinutes: Double)

/** Statistics data downloaded for arbitrary date ranges, kept for the life of the process. */
object StatsStore {
    val bgData = mutableListOf<ShareGlucoseData>()
    val bolusData = mutableListOf<BolusGraphPoint>()
    val smbData = mutableListOf<BolusGraphPoint>()
    val carbData = mutableListOf<CarbGraphPoint>()
    val basalData = mutableListOf<BasalGraphPoint>()
    // ANDROID-DEVIATION: Kept for the app session, so Actual Basal survives reopening the screen with otherwise complete data.
    val tempBasalEntries = mutableListOf<TempBasalEntry>()
}

/** The selected date range and the data inside it. */
class StatsDataService {
    var daysToAnalyze: Int = 14
    var startDate: Double = StatsCalendar.addDays(nowSeconds(), -14)
    var endDate: Double = nowSeconds()

    private val dataFetcher = StatsDataFetcher(this)

    fun updateDateRange(start: Double, end: Double) {
        startDate = start
        endDate = end
        daysToAnalyze = StatsCalendar.daysBetween(start, end).toInt() + 1
    }

    /** Downloads BG and treatment data when the stored data does not cover the range. */
    suspend fun ensureDataAvailable() {
        val cutoffTime = startDate
        val endTime = endDate
        fun inRange(date: Double) = date >= cutoffTime && date <= endTime

        val oldestBG = StatsStore.bgData.filter { inRange(it.date) }.minOfOrNull { it.date }
        val oldestBolus = StatsStore.bolusData.filter { inRange(it.date) }.minOfOrNull { it.date }
        val oldestCarb = StatsStore.carbData.filter { inRange(it.date) }.minOfOrNull { it.date }
        val oldestBasal = StatsStore.basalData.filter { inRange(it.date) }.minOfOrNull { it.date }

        val bgDataCount = StatsStore.bgData.count { inRange(it.date) }
        val bolusDataCount = StatsStore.bolusData.count { inRange(it.date) }
        val carbDataCount = StatsStore.carbData.count { inRange(it.date) }
        val basalDataCount = StatsStore.basalData.count { inRange(it.date) }

        val day = 24 * 60 * 60
        val minExpectedBGEntries = maxOf(daysToAnalyze * 6, 12)
        val hasEnoughBGData = bgDataCount >= minExpectedBGEntries && (oldestBG ?: endTime) <= cutoffTime + day
        val minExpectedTreatmentEntries = maxOf(daysToAnalyze, 1)
        val hasEnoughTreatmentData = (bolusDataCount + carbDataCount + basalDataCount) >= minExpectedTreatmentEntries &&
            (oldestBolus ?: endTime) <= cutoffTime + day &&
            (oldestCarb ?: endTime) <= cutoffTime + day &&
            (oldestBasal ?: endTime) <= cutoffTime + day

        if (!hasEnoughBGData) dataFetcher.fetchBGData(daysToAnalyze)
        if (!hasEnoughTreatmentData) dataFetcher.fetchTreatmentsData(daysToAnalyze)
    }

    private fun inRange(date: Double) = date >= startDate && date <= endDate

    fun getBGData(): List<ShareGlucoseData> = StatsStore.bgData.filter { inRange(it.date) }

    fun getBolusData(): List<BolusGraphPoint> = StatsStore.bolusData.filter { inRange(it.date) }

    fun getSMBData(): List<BolusGraphPoint> = StatsStore.smbData.filter { inRange(it.date) }

    fun getCarbData(): List<CarbGraphPoint> = StatsStore.carbData.filter { inRange(it.date) }

    fun getBasalProfile(): List<BasalProfileEntry> = MainEngine.basalProfile

    fun getTempBasalData(): List<TempBasalEntry> = StatsStore.tempBasalEntries.filter { inRange(it.startTime) }

    fun getDataAvailability(): DataAvailabilityInfo =
        DataAvailabilityCalculator.calculateAvailability(getBGData(), startDate, endDate)
}
