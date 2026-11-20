package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.launch

fun MainEngine.scheduleProfileTask(initialDelay: Double = 3.0) {
    TaskScheduler.scheduleTask(TaskID.PROFILE, millisFromNow(initialDelay)) { profileTaskAction() }
}

fun MainEngine.profileTaskAction() {
    if (!isNightscoutEnabled()) {
        TaskScheduler.rescheduleTaskIn(TaskID.PROFILE, 60.0)
        return
    }
    webLoadNSProfile()
    val interval = if (NightscoutSocketManager.isAuthenticated) 30 * 60.0 else 10 * 60.0
    TaskScheduler.rescheduleTaskIn(TaskID.PROFILE, interval)
}

fun MainEngine.webLoadNSProfile() {
    val parameters = mapOf(
        "count" to "1",
        "find[startDate][\$lte]" to NightscoutUtils.formatISO(nowSeconds() + 60, fractional = true),
    )
    scope.launch {
        val profiles = try {
            NightscoutUtils.executeRequest(EventType.PROFILE, parameters).objectList()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "webLoadNSProfile, error fetching profile data: ${e.message}")
            return@launch
        }
        val profileData = profiles.firstOrNull()
        if (profileData == null) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "webLoadNSProfile, no profile records returned")
            return@launch
        }
        updateProfile(NSProfile(profileData))
    }
}

fun MainEngine.updateProfile(profileData: NSProfile) {
    val store = profileData.store["default"] ?: profileData.store["Default"] ?: run {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "Profile document not loaded, its Default entry could not be read")
        return
    }
    profileManager.loadProfile(profileData)
    infoManager.update(InfoType.PROFILE, profileData.defaultProfile)
    Storage.lastProfileName.value = profileData.defaultProfile

    markDataLoaded("profile")

    basalProfile = store.basal.map { BasalProfileEntry(it.value, it.time, it.timeAsSeconds) }

    // Don't process the basal or draw the graph until after the BG has been processed and drawn.
    if (firstGraphLoad || basalProfile.isEmpty()) return

    data class Segment(val basalRate: Double, val startDate: Double, val endDate: Double)
    val basalSegments = mutableListOf<Segment>()

    val graphHours = 24 * Storage.downloadDays.value
    // Build scheduled basal segments from right to left.
    var midnight = DateTimeUtils.midnightToday()
    var index = basalProfile.size - 1
    var start = midnight + basalProfile[index].timeAsSeconds
    var end = nowSeconds()
    while (start > end) {
        index -= 1
        // ANDROID-DEVIATION: before today's first entry the schedule wraps to yesterday's last entry; iOS indexes
        // past the start of the schedule when the first entry starts after midnight.
        if (index < 0) {
            index = basalProfile.size - 1
            midnight -= 24 * 60 * 60
        }
        start = midnight + basalProfile[index].timeAsSeconds
    }
    val graphStart = DateTimeUtils.nHoursAgo(graphHours)
    while (end >= graphStart) {
        basalSegments.add(Segment(basalProfile[index].value, start, end))
        index -= 1
        if (index < 0) {
            index = basalProfile.size - 1
            midnight -= 24 * 60 * 60
        }
        end = start - 1
        start = midnight + basalProfile[index].timeAsSeconds
    }
    basalSegments.reverse()

    var firstPass = true
    val predictionEndTime = nowSeconds() + 3600 * Storage.predictionToLoad.value
    val result = mutableListOf<BasalGraphPoint>()

    for (i in basalSegments.indices) {
        val timeStart = DateTimeUtils.nHoursAgo(graphHours)
        val seg = basalSegments[i]

        if (!firstPass && seg.startDate <= predictionEndTime) {
            result.add(BasalGraphPoint(seg.basalRate, seg.startDate))
            var endDate = seg.endDate
            if (endDate > predictionEndTime || i == basalSegments.size - 1) endDate = predictionEndTime
            result.add(BasalGraphPoint(seg.basalRate, endDate))
        }

        if (firstPass && timeStart >= seg.startDate && timeStart < seg.endDate) {
            result.add(BasalGraphPoint(seg.basalRate, timeStart + 60 * 5))
            result.add(BasalGraphPoint(seg.basalRate, seg.endDate))
            firstPass = false
        }
    }
    basalScheduleData = result

    if (Storage.graphBasal.value) updateBasalScheduledGraph()
}
