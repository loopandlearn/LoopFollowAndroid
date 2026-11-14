package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.service.OngoingNotification

import com.loopandlearn.loopfollow.helpers.CycleHelper
import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Number of days of BG history to request; one extra for the "yesterday" overlay. */
val MainEngine.bgFetchDays: Int
    get() {
        val extraDay = if (Storage.showYesterdayLine.value && isNightscoutEnabled()) 1 else 0
        return Storage.downloadDays.value + extraDay
    }

fun MainEngine.scheduleBGTask(initialDelay: Double = 2.0) {
    TaskScheduler.scheduleTask(TaskID.FETCH_BG, millisFromNow(initialDelay)) { bgTaskAction() }
}

fun MainEngine.bgTaskAction() {
    // If anything goes wrong, try again in 60 seconds.
    TaskScheduler.rescheduleTaskIn(TaskID.FETCH_BG, 60.0)

    // A deconfigured source has no error state.
    if (Storage.shareUserName.value.isEmpty() || Storage.sharePassword.value.isEmpty()) {
        BannerManager.clear(BannerSource.Dexcom)
    }
    if (!isNightscoutEnabled()) BannerManager.clear(BannerSource.Nightscout)

    if (Storage.shareUserName.value.isEmpty() && Storage.sharePassword.value.isEmpty() && !isNightscoutEnabled()) {
        return
    }

    if (Storage.shareUserName.value.isNotEmpty() && Storage.sharePassword.value.isNotEmpty()) {
        webLoadDexShare()
    } else {
        webLoadNSBGData()
    }
}

fun MainEngine.webLoadDexShare() {
    // Dexcom Share only returns 24 hrs of data; more is requested for consistency with NS.
    val graphHours = 24 * bgFetchDays
    val count = graphHours * 12
    scope.launch {
        val data = try {
            DexcomShare.client?.fetchData(count) ?: emptyList()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.DEXCOM, "Error fetching Dexcom data: ${e.message}", limitIdentifier = "Error fetching Dexcom data")
            BannerManager.reportDexcomFailure(e, isNightscoutEnabled())
            webLoadNSBGData()
            return@launch
        }

        if (data.isEmpty()) {
            LogManager.log(LogManager.Category.DEXCOM, "Received empty data array from Dexcom", limitIdentifier = "Received empty data array from Dexcom")
            webLoadNSBGData()
            return@launch
        }

        BannerManager.clear(BannerSource.Dexcom)

        // If Dex data is old, load from NS instead.
        val latestDate = data[0].date
        if (latestDate + 330 < nowSeconds() && isNightscoutEnabled()) {
            LogManager.log(LogManager.Category.DEXCOM, "Dexcom data is old, loading from NS instead", limitIdentifier = "Dexcom data is old, loading from NS instead")
            webLoadNSBGData()
            return@launch
        }

        // Dexcom Share can return duplicates when several uploaders write to one account.
        val deduped = deduplicateBGReadings(data)

        // Supplement with NS if Dex data doesn't cover the full requested window.
        val dexCutoff = nowSeconds() - graphHours * 3600.0
        val dexCoversFull = deduped.lastOrNull()?.let { it.date <= dexCutoff } ?: false
        if (!dexCoversFull && isNightscoutEnabled()) {
            webLoadNSBGData(deduped)
        } else {
            processDexBGData(deduped, "Dexcom")
        }
    }
}

fun MainEngine.webLoadNSBGData(dexData: List<ShareGlucoseData> = emptyList()) {
    if (!isNightscoutEnabled()) {
        BannerManager.clear(BannerSource.Nightscout)
        Storage.lastBGChecked.value = System.currentTimeMillis()
        return
    }

    val since = System.currentTimeMillis() - bgFetchDays * 86_400_000L
    val parameters = mapOf(
        "count" to "${bgFetchDays * Globals.MAX_EXPECTED_UPLOADERS * 24 * 60 / 5}",
        "find[date][\$gte]" to "$since",
        // Exclude 'cal' entries
        "find[type][\$ne]" to "cal",
    )

    scope.launch {
        val entries = try {
            NightscoutUtils.executeRequest(EventType.SGV, parameters).objectList()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Failed to fetch bg data: $e", limitIdentifier = "Failed to fetch bg data")
            BannerManager.reportNightscoutFetchFailure(e)
            TaskScheduler.rescheduleTaskIn(TaskID.FETCH_BG, 10.0)
            if (dexData.isNotEmpty()) {
                processDexBGData(dexData, "Dexcom")
            } else {
                Storage.lastBGChecked.value = System.currentTimeMillis()
            }
            return@launch
        }

        BannerManager.clear(BannerSource.Nightscout)

        // Transform NS data to look like Dex data, with dates in seconds.
        val nsData = entries.mapNotNull { e ->
            val value = e.double("sgv") ?: e.double("mbg") ?: e.double("glucose") ?: return@mapNotNull null
            val date = e.double("date") ?: return@mapNotNull null
            ShareGlucoseData(value.roundToInt(), (date / 1000).roundHalfEven(), e.string("direction"))
        }

        var nsData2 = deduplicateBGReadings(nsData)

        // Merge NS and Dex data if needed; use recent Dex data and older NS data.
        var sourceName = "Nightscout"
        if (dexData.isNotEmpty()) {
            val oldestDexDate = dexData.last().date
            nsData2 = dexData + nsData2.dropWhile { it.date >= oldestDexDate }
            sourceName = "Dexcom"
        }
        processDexBGData(nsData2, sourceName)
    }
}

private fun Double.roundHalfEven(): Double = Math.rint(this)

/** Removes consecutive duplicate readings (same SGV within 30 s). Expects newest-first input. */
fun deduplicateBGReadings(readings: List<ShareGlucoseData>): List<ShareGlucoseData> {
    val result = mutableListOf<ShareGlucoseData>()
    var lastTime = Double.POSITIVE_INFINITY
    var lastSGV: Int? = null
    for (reading in readings) {
        if (lastSGV == null || lastSGV != reading.sgv || lastTime - reading.date >= 30) {
            result.add(reading)
            lastTime = reading.date
            lastSGV = reading.sgv
        }
    }
    return result
}

/** Processes incoming BG data (newest first). */
fun MainEngine.processDexBGData(data: List<ShareGlucoseData>, sourceName: String) {
    val graphHours = 24 * Storage.downloadDays.value

    if (data.isEmpty()) {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "No bg data received. Skipping processing.", limitIdentifier = "No bg data received. Skipping processing.")
        Storage.lastBGChecked.value = System.currentTimeMillis()
        return
    }

    val sensorTimestamp = data[0].date
    val secondsAgo = nowSeconds() - sensorTimestamp

    val currentOffset = CycleHelper.cycleOffset(sensorTimestamp, 5 * 60.0)
    if (Storage.sensorScheduleOffset.value != currentOffset) {
        Storage.sensorScheduleOffset.value = currentOffset
        LogManager.log(LogManager.Category.NIGHTSCOUT, "Sensor schedule offset: $currentOffset seconds.", isDebug = true)
    }

    var delayToSchedule = when {
        secondsAgo >= 20 * 60 -> 5 * 60.0
        secondsAgo >= 10 * 60 -> 60.0
        secondsAgo >= 7 * 60 -> 30.0
        secondsAgo >= 5 * 60 -> 5.0
        else -> {
            TaskScheduler.rescheduleTaskIn(TaskID.ALARM_CHECK, 3.0)
            300 - secondsAgo + Storage.bgUpdateDelay.value
        }
    }
    if (NightscoutSocketManager.isAuthenticated) delayToSchedule = maxOf(delayToSchedule * 3, 60.0)
    TaskScheduler.rescheduleTaskIn(TaskID.FETCH_BG, delayToSchedule)

    // Graph data, oldest first.
    val cutoff = nowSeconds() - graphHours * 3600.0
    bgData = data.asReversed().filter { reading ->
        if (reading.date < cutoff) return@filter false
        if (reading.sgv > 600) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Skipping reading with sgv ${reading.sgv} as it exceeds threshold.", isDebug = true)
            return@filter false
        }
        true
    }.map { ShareGlucoseData(it.sgv, it.date, it.direction) }

    LogManager.log(LogManager.Category.NIGHTSCOUT, "Graph data updated with ${bgData.size} entries.", isDebug = true)

    // Speaking is once per reading, judged by the newest entry of bgData.
    if (data.size > 1) SpeakBG.evaluateSpeakConditions(data[0].sgv, data[1].sgv)

    // "Yesterday" overlay: every reading shifted +24h, capped to now + hours of prediction.
    yesterdayBGData = if (Storage.showYesterdayLine.value && isNightscoutEnabled()) {
        val yCutoff = nowSeconds() - 24.0 * bgFetchDays * 3600
        val futureLimit = nowSeconds() + Storage.predictionToLoad.value * 3600
        data.asReversed().mapNotNull { r ->
            if (r.date < yCutoff || r.sgv > 600) return@mapNotNull null
            val shifted = r.date + 24 * 60 * 60
            if (shifted > futureLimit) null else ShareGlucoseData(r.sgv, shifted, r.direction)
        }
    } else {
        emptyList()
    }

    viewUpdateNSBG(sourceName)
}

fun MainEngine.updateServerText(serverText: String? = null) {
    if (Storage.showDisplayName.value) {
        Observable.serverText.value = "LoopFollow"
    } else if (serverText != null) {
        Observable.serverText.value = serverText
    }
}

fun MainEngine.viewUpdateNSBG(sourceName: String) {
    TaskScheduler.rescheduleTask(TaskID.MIN_AGO_UPDATE, System.currentTimeMillis())

    val entries = bgData
    if (entries.size < 2) {
        Storage.lastBGChecked.value = System.currentTimeMillis()
        return
    }

    updateBGGraph()
    updateStats()
    updateCGMSensorState()

    val latest = entries.last()
    val prior = entries[entries.size - 2]
    val deltaBG = latest.sgv - prior.sgv

    updateServerText(sourceName)
    updateBGTextAppearance()

    Observable.bgText.value = when {
        latest.sgv <= Globals.MIN_DISPLAY_GLUCOSE -> "LOW"
        latest.sgv >= Globals.MAX_DISPLAY_GLUCOSE -> "HIGH"
        else -> Localizer.toDisplayUnits(latest.sgv)
    }
    Observable.bg.value = latest.sgv

    Observable.directionText.value = latest.direction?.let { bgDirectionGraphic(it) } ?: ""

    Observable.deltaText.value = if (deltaBG < 0) {
        Localizer.toDisplayUnits(deltaBG)
    } else {
        "+" + Localizer.toDisplayUnits(deltaBG)
    }

    Storage.lastBgReadingTimeSeconds.value = latest.date
    Storage.lastDeltaMgdl.value = deltaBG.toDouble()
    Storage.lastTrendCode.value = latest.direction

    markDataLoaded("bg")
    OngoingNotification.refresh()

    Storage.lastBGChecked.value = System.currentTimeMillis()
}

/** The reading at or before [needle] (epoch s), searching from [startingIndex]; 100 when none. */
fun findNearestBGbyTime(needle: Double, haystack: List<ShareGlucoseData>, startingIndex: Int): Pair<Double, Int> {
    if (startingIndex > haystack.size) return 100.0 to 0
    for (i in startingIndex until haystack.size) {
        if (i == haystack.size - 1) return 100.0 to 0
        if (needle >= haystack[i].date && needle < haystack[i + 1].date) return haystack[i].sgv.toDouble() to i
    }
    return 100.0 to 0
}

fun findNearestBolusbyTime(timeWithin: Int, needle: Double, haystack: List<BolusGraphPoint>, startingIndex: Int): Pair<Boolean, Int> {
    for (i in startingIndex until haystack.size) {
        val timeDiff = needle - haystack[i].date
        if (timeDiff <= timeWithin && timeDiff >= -timeWithin) return true to i
        if (i == haystack.size - 1) return false to 0
        if (timeDiff < -timeWithin) return false to 0
    }
    return false to 0
}
