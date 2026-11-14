package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.service.OngoingNotification

import com.loopandlearn.loopfollow.helpers.CGMSensorState
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.helpers.positionalDaysHours
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.info.isVisible
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.int
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

fun MainEngine.scheduleTreatmentsTask(initialDelay: Double = 5.0) {
    TaskScheduler.scheduleTask(TaskID.TREATMENTS, millisFromNow(initialDelay)) { treatmentsTaskAction() }
}

fun MainEngine.treatmentsTaskAction() {
    if (!isNightscoutEnabled() || !Storage.downloadTreatments.value) {
        if (cgmSensorStates.isNotEmpty()) {
            cgmSensorStates = emptyList()
            updateCGMSensorState()
        }
        TaskScheduler.rescheduleTaskIn(TaskID.TREATMENTS, 60.0)
        return
    }
    webLoadNSTreatments()
    val interval = if (NightscoutSocketManager.isAuthenticated) 10 * 60.0 else 2 * 60.0
    TaskScheduler.rescheduleTaskIn(TaskID.TREATMENTS, interval)
    TaskScheduler.rescheduleTaskIn(TaskID.ALARM_CHECK, 3.0)
}

/**
 * Nightscout duplicates can have different `_id` values. Trio FPU siblings share an `id`
 * but have distinct times, so entries are deduplicated by logical occurrence.
 */
fun deduplicatedTreatmentEntries(entries: List<JsonObject>): List<JsonObject> {
    val seen = HashSet<Triple<String, String?, Double?>>()
    return entries.filter { entry ->
        val id = entry.string("id")
        if (id.isNullOrEmpty()) return@filter true
        val eventType = entry.string("eventType")
        val rawDate = when (eventType) {
            "Pump Site Change", "Site Change", "Sensor Start", "Insulin Change" -> entry.string("created_at")
            else -> entry.string("timestamp") ?: entry.string("created_at")
        }
        seen.add(Triple(id, eventType, rawDate?.let { NightscoutUtils.parseDate(it) }))
    }
}

/** Downloads basal, bolus, carbs, BG checks, notes, overrides and temp targets. */
fun MainEngine.webLoadNSTreatments() {
    if (!Storage.downloadTreatments.value) return

    val parameters = mapOf(
        "find[created_at][\$gte]" to DateTimeUtils.dateTimeString(addingDays = -Storage.downloadDays.value),
        "find[created_at][\$lte]" to DateTimeUtils.dateTimeString(addingHours = 6),
        "count" to "${maxOf(Storage.downloadDays.value * 100, 5000)}",
    )
    scope.launch {
        val data = try {
            NightscoutUtils.executeRequest(EventType.TREATMENTS, parameters)
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "WebLoadNSTreatments, error ${e.message}")
            return@launch
        }
        if (data !is JsonArray) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "WebLoadNSTreatments, Unexpected data structure")
            return@launch
        }
        updateTreatments(data.objectList())
    }
}

/** Splits treatments by type and processes each kind. */
fun MainEngine.updateTreatments(entries: List<JsonObject>) {
    val unique = deduplicatedTreatmentEntries(entries)

    val tempBasal = mutableListOf<JsonObject>()
    val bolus = mutableListOf<JsonObject>()
    val smb = mutableListOf<JsonObject>()
    val carbs = mutableListOf<JsonObject>()
    val temporaryOverride = mutableListOf<JsonObject>()
    val temporaryTarget = mutableListOf<JsonObject>()
    val note = mutableListOf<JsonObject>()
    val bgCheck = mutableListOf<JsonObject>()
    val suspendPump = mutableListOf<JsonObject>()
    val resumePump = mutableListOf<JsonObject>()
    val pumpSiteChange = mutableListOf<String>()
    val cgmSensorStart = mutableListOf<String>()
    val insulinCartridge = mutableListOf<String>()

    for (entry in unique) {
        when (entry.string("eventType") ?: continue) {
            "Temp Basal" -> tempBasal.add(entry)
            "Correction Bolus", "Bolus", "External Insulin" ->
                if (entry.bool("automatic") == true) smb.add(entry) else bolus.add(entry)
            "SMB" -> smb.add(entry)
            "Meal Bolus" -> {
                carbs.add(entry)
                bolus.add(entry)
            }
            "Carb Correction" -> carbs.add(entry)
            "Temporary Override", "Exercise" -> temporaryOverride.add(entry)
            "Temporary Target" -> temporaryTarget.add(entry)
            "Note" -> note.add(entry)
            "BG Check" -> bgCheck.add(entry)
            "Suspend Pump" -> suspendPump.add(entry)
            "Resume Pump" -> resumePump.add(entry)
            "Pump Site Change", "Site Change" -> entry.string("created_at")?.let { pumpSiteChange.add(it) }
            "Sensor Start" -> entry.string("created_at")?.let { cgmSensorStart.add(it) }
            "Insulin Change" -> entry.string("created_at")?.let { insulinCartridge.add(it) }
        }
    }

    if (tempBasal.isNotEmpty()) {
        val suspensions = PumpSuspension.intervals(
            notes = note.mapNotNull { e ->
                val date = NightscoutUtils.treatmentDate(e) ?: return@mapNotNull null
                val text = e.string("notes") ?: return@mapNotNull null
                date to text
            },
            tempBasalStarts = tempBasal.mapNotNull { NightscoutUtils.treatmentDate(it) },
        )
        processNSBasals(tempBasal, suspensions)
    } else {
        if (basalData.isNotEmpty()) {
            basalData = emptyList()
            updateBasalGraph()
        }
        profileManager.currentBasal()?.let {
            infoManager.update(InfoType.BASAL, it)
            Storage.lastBasal.value = it
        }
    }

    if (bolus.isNotEmpty()) processNSBolus(bolus) else if (bolusData.isNotEmpty()) {
        bolusData = emptyList()
        updateBolusGraph()
    }
    if (smb.isNotEmpty()) processNSSmb(smb) else if (smbData.isNotEmpty()) {
        smbData = emptyList()
        updateSmbGraph()
    }
    updateTodaysCarbsFromEntries(carbs)
    if (carbs.isNotEmpty()) processNSCarbs(carbs) else if (carbData.isNotEmpty()) {
        carbData = emptyList()
        updateCarbGraph()
    }
    if (bgCheck.isNotEmpty()) processNSBGCheck(bgCheck) else if (bgCheckData.isNotEmpty()) {
        bgCheckData = emptyList()
        updateBGCheckGraph()
    }
    if (temporaryOverride.isEmpty() && overrideGraphData.isNotEmpty()) {
        overrideGraphData = emptyList()
        Observable.override.value = null
        Observable.overrideEndAt.value = null
        updateOverrideGraph()
    }
    if (temporaryOverride.isNotEmpty()) processNSOverrides(temporaryOverride)

    if (temporaryTarget.isEmpty() && tempTargetGraphData.isNotEmpty()) {
        tempTargetGraphData = emptyList()
        Observable.tempTarget.value = null
        Observable.tempTargetEndAt.value = null
        updateTempTargetGraph()
    }
    if (temporaryTarget.isNotEmpty()) processNSTemporaryTarget(temporaryTarget)

    if (suspendPump.isNotEmpty()) {
        suspendGraphData = timestampPoints(suspendPump)
        if (Storage.graphOtherTreatments.value) updateSuspendGraph()
    } else if (suspendGraphData.isNotEmpty()) {
        suspendGraphData = emptyList()
        updateSuspendGraph()
    }
    if (resumePump.isNotEmpty()) {
        resumeGraphData = timestampPoints(resumePump)
        if (Storage.graphOtherTreatments.value) updateResumeGraph()
    } else if (resumeGraphData.isNotEmpty()) {
        resumeGraphData = emptyList()
        updateResumeGraph()
    }

    processSage(cgmSensorStart)
    if (cgmSensorStart.isNotEmpty()) processSensorStart(cgmSensorStart) else if (sensorStartGraphData.isNotEmpty()) {
        sensorStartGraphData = emptyList()
        updateSensorStart()
    }

    processIage(insulinCartridge)

    if (note.isNotEmpty()) processNotes(note) else if (noteGraphData.isNotEmpty() || cgmSensorStates.isNotEmpty()) {
        noteGraphData = emptyList()
        cgmSensorStates = emptyList()
        updateNotes()
        updateCGMSensorState()
    }
    processCage(pumpSiteChange)
}

private fun MainEngine.timestampPoints(entries: List<JsonObject>): List<TimestampPoint> {
    var lastFoundIndex = 0
    val result = mutableListOf<TimestampPoint>()
    for (entry in entries.asReversed()) {
        val dateStr = entry.string("timestamp") ?: entry.string("created_at") ?: continue
        val date = NightscoutUtils.parseDate(dateStr) ?: continue
        val (sgv, found) = findNearestBGbyTime(date, bgData, lastFoundIndex)
        lastFoundIndex = found
        if (date < nowSeconds() + 60 * 60) result.add(TimestampPoint(date, sgv.toInt()))
    }
    return result
}

/** NS temp basal processor. */
fun MainEngine.processNSBasals(entries: List<JsonObject>, suspensions: List<ClosedRange<Double>> = emptyList()) {
    infoManager.clear(InfoType.BASAL)

    val result = mutableListOf<BasalGraphPoint>()
    var lastEndDot = 0.0
    val tempArray = entries.asReversed()

    fun dateOf(e: JsonObject?): Double? {
        val s = e?.string("timestamp") ?: e?.string("created_at") ?: return null
        return NightscoutUtils.parseDate(s)
    }

    for (i in tempArray.indices) {
        val currentEntry = tempArray[i]
        val dateTimeStamp = dateOf(currentEntry) ?: continue
        val basalRate = currentEntry.double("absolute") ?: continue
        val duration = currentEntry.double("duration") ?: 0.0

        if (i > 0) {
            val priorEntry = tempArray[i - 1]
            val priorDateTimeStamp = dateOf(priorEntry)
            if (priorDateTimeStamp != null) {
                val priorDuration = priorEntry.double("duration") ?: 0.0
                if (dateTimeStamp - priorDateTimeStamp > priorDuration * 60 + 15) {
                    var scheduled = 0.0
                    var midGap = false
                    var midGapTime = 0.0
                    var midGapValue = 0.0
                    for (b in basalScheduleData.indices) {
                        val priorEnd = priorDateTimeStamp + priorDuration * 60
                        if (priorEnd >= basalScheduleData[b].date) {
                            scheduled = basalScheduleData[b].basalRate
                            if (b < basalScheduleData.size - 1 && dateTimeStamp > basalScheduleData[b + 1].date) {
                                midGap = true
                                midGapTime = basalScheduleData[b + 1].date
                                midGapValue = basalScheduleData[b + 1].basalRate
                            }
                        }
                    }
                    result.add(BasalGraphPoint(scheduled, priorDateTimeStamp + priorDuration * 60))
                    if (midGap) {
                        result.add(BasalGraphPoint(scheduled, midGapTime))
                        result.add(BasalGraphPoint(midGapValue, midGapTime))
                        result.add(BasalGraphPoint(midGapValue, dateTimeStamp))
                    } else {
                        result.add(BasalGraphPoint(scheduled, dateTimeStamp))
                    }
                }
            }
        }

        result.add(BasalGraphPoint(basalRate, dateTimeStamp))

        // A duration of 0 is a temp that ended where it started; only a missing duration
        // is taken as still running.
        var lastDot = dateTimeStamp + duration * 60
        if (i == tempArray.size - 1 && !currentEntry.containsKey("duration")) {
            lastDot = dateTimeStamp + 30 * 60
        }
        latestBasal = Localizer.formatToLocalizedString(basalRate, 2, 0)

        if (i < tempArray.size - 1) {
            val nextDateTimeStamp = dateOf(tempArray[i + 1])
            if (nextDateTimeStamp != null && nextDateTimeStamp < dateTimeStamp + duration * 60) {
                lastDot = nextDateTimeStamp
            }
        }

        result.add(BasalGraphPoint(basalRate, lastDot))
        lastEndDot = lastDot
    }

    // If the last basal ended before now, fill up to now with the scheduled basal.
    if (lastEndDot <= nowSeconds()) {
        val now = nowSeconds()
        val tailStart = maxOf(lastEndDot, DateTimeUtils.nHoursAgo(24 * Storage.downloadDays.value))
        val tailEnd = now + 60 * 10

        latestBasal = Localizer.formatToLocalizedString(profileManager.basalRate(System.currentTimeMillis()) ?: 0.0, 2, 0)

        var segments = profileManager.scheduledBasalSegments(tailStart, tailEnd)
        if (segments.isEmpty()) segments = listOf(ProfileManager.BasalSegment(tailStart, 0.0))
        for ((index, segment) in segments.withIndex()) {
            val segmentEnd = if (index < segments.size - 1) segments[index + 1].start else tailEnd
            result.add(BasalGraphPoint(segment.rate, segment.start))
            result.add(BasalGraphPoint(segment.rate, segmentEnd))
        }
    }

    basalData = PumpSuspension.applying(suspensions, result)
    if (suspensions.any { nowSeconds() in it }) {
        latestBasal = Localizer.formatToLocalizedString(0.0, 2, 0)
    }

    if (Storage.graphBasal.value) updateBasalGraph()

    val profileBasal = profileManager.currentBasal()
    if (profileBasal != null && profileBasal != latestBasal) {
        latestBasal = "$profileBasal → $latestBasal"
    }
    infoManager.update(InfoType.BASAL, latestBasal)
    Storage.lastBasal.value = latestBasal
}

private fun MainEngine.bolusPoints(entries: List<JsonObject>): List<BolusGraphPoint> {
    val result = mutableListOf<BolusGraphPoint>()
    var lastFoundIndex = 0
    for (entry in entries.asReversed()) {
        val date = NightscoutUtils.treatmentDate(entry) ?: continue
        val bolus = entry.double("insulin") ?: continue
        val (sgv, found) = findNearestBGbyTime(date, bgData, lastFoundIndex)
        lastFoundIndex = found
        if (date < nowSeconds() + 60 * 60) {
            result.add(BolusGraphPoint(bolus, date, (sgv + 20).toInt(), Treatment.bolus(entry, date, sgv.toInt())))
        }
    }
    return result
}

fun MainEngine.processNSBolus(entries: List<JsonObject>) {
    bolusData = bolusPoints(entries)
    if (Storage.graphBolus.value) updateBolusGraph()
}

fun MainEngine.processNSSmb(entries: List<JsonObject>) {
    smbData = bolusPoints(entries)
    if (Storage.graphBolus.value) updateSmbGraph()
}

fun MainEngine.processNSCarbs(entries: List<JsonObject>) {
    val result = mutableListOf<CarbGraphPoint>()
    var lastFoundIndex = 0
    var lastFoundBolus = 0
    var lastFoundSmb = 0

    for (entry in entries.asReversed()) {
        val absorptionTime = entry.int("absorptionTime") ?: 0
        val date = NightscoutUtils.treatmentDate(entry) ?: continue
        val carbs = entry.double("carbs") ?: continue

        val (sgv, found) = findNearestBGbyTime(date, bgData, lastFoundIndex)
        lastFoundIndex = found

        var offset = -50
        if (sgv < calculateMaxBgGraphValue() - 100) {
            val bolusTime = findNearestBolusbyTime(300, date, bolusData, lastFoundBolus)
            lastFoundBolus = bolusTime.second
            val smbTime = findNearestBolusbyTime(300, date, smbData, lastFoundSmb)
            lastFoundSmb = smbTime.second
            offset = if (bolusTime.first || smbTime.first) 70 else 20
        }

        if (date < nowSeconds() + 3600 * Storage.predictionToLoad.value) {
            result.add(CarbGraphPoint(carbs, date, (sgv + offset).toInt(), absorptionTime, Treatment.carb(entry, date, sgv.toInt())))
        }
    }
    carbData = result
    if (Storage.graphCarbs.value) updateCarbGraph()
}

fun MainEngine.updateTodaysCarbsFromEntries(entries: List<JsonObject>) {
    var total = 0.0
    for (entry in entries) {
        val raw = entry.string("timestamp") ?: entry.string("created_at") ?: continue
        val date = NightscoutUtils.parseDate(raw) ?: continue
        if (DateTimeUtils.isToday(date)) {
            entry.double("carbs")?.let { total += it }
        }
    }
    infoManager.update(InfoType.CARBS_TODAY, String.format("%.0f", total), total)
    Storage.lastCarbsToday.value = total
}

fun MainEngine.processNSBGCheck(entries: List<JsonObject>) {
    val result = mutableListOf<ShareGlucoseData>()
    for (entry in entries.asReversed()) {
        val dateStr = entry.string("timestamp") ?: entry.string("created_at") ?: continue
        val date = NightscoutUtils.parseDate(dateStr) ?: continue
        val glucose = entry.double("glucose") ?: continue
        val units = entry.string("units") ?: "mg/dl"
        val converted = if (units == "mmol") glucose * GlucoseConversion.MMOL_TO_MGDL else glucose
        if (date < nowSeconds() + 60 * 60) result.add(ShareGlucoseData(converted.roundToInt(), date, ""))
    }
    bgCheckData = result
    if (Storage.graphOtherTreatments.value) updateBGCheckGraph()
}

fun MainEngine.processNSOverrides(entries: List<JsonObject>) {
    fun dateOf(e: JsonObject): Double? = (e.string("timestamp") ?: e.string("created_at"))?.let { NightscoutUtils.parseDate(it) }

    val sorted = entries.sortedWith(compareBy<JsonObject, Double?>(nullsLast()) { dateOf(it) })

    var activeOverrideNote: String? = null
    var activeOverrideEndAt: Double? = null

    val now = nowSeconds()
    val effectiveFutureHours = maxOf(Storage.predictionToLoad.value, 0.25)
    val maxEndDate = now + effectiveFutureHours * 3600
    val graphHorizon = DateTimeUtils.nHoursAgo(24 * Storage.downloadDays.value)

    val result = mutableListOf<OverrideSpan>()
    for (i in sorted.indices) {
        val e = sorted[i]
        val startDate = dateOf(e) ?: continue
        val start = maxOf(startDate, graphHorizon)
        val nextStart = if (i + 1 < sorted.size) dateOf(sorted[i + 1]) else null

        val durationSeconds = (e.double("duration") ?: 5.0) * 60
        // Loop marks indefinite overrides explicitly; Trio uses a ~30-day duration.
        val isIndefinite = e.string("durationType") == "indefinite" || durationSeconds >= 7 * 24 * 3600

        var end = if (isIndefinite) maxEndDate else start + durationSeconds
        var trueEnd: Double? = if (isIndefinite) null else startDate + durationSeconds

        if (nextStart != null) {
            end = minOf(end, nextStart - 60)
            trueEnd = trueEnd?.let { minOf(it, nextStart - 60) }
        }
        end = minOf(end, maxEndDate)
        if (end - start < 300) continue

        val correctionRange = (e["correctionRange"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?.takeIf { it.size == 2 }
            ?: listOf(e.int("targetBottom") ?: 0, e.int("targetTop") ?: 0)

        // Loop stores the override name in "reason"; Trio stores it in "notes".
        val reason = e.string("notes")?.trim() ?: e.string("reason")?.trim() ?: ""

        result.add(
            OverrideSpan(
                insulNeedsScaleFactor = e.double("insulinNeedsScaleFactor") ?: 1.0,
                date = start,
                endDate = end,
                duration = end - start,
                correctionRange = correctionRange,
                enteredBy = e.string("enteredBy") ?: "unknown",
                reason = reason,
                sgv = -20f,
                scheduledEndDate = trueEnd,
                detail = Treatment.firstEntry(e, startDate),
            ),
        )

        if (now >= start && now < end) {
            activeOverrideNote = e.string("notes") ?: e.string("reason")
            activeOverrideEndAt = trueEnd
        }
    }
    overrideGraphData = result

    Observable.override.value = activeOverrideNote
    Observable.overrideEndAt.value = activeOverrideEndAt
    if (Storage.device.value != "Loop") {
        if (activeOverrideNote != null) infoManager.update(InfoType.OVERRIDE, activeOverrideNote) else infoManager.clear(InfoType.OVERRIDE)
    }
    if (Storage.graphOtherTreatments.value) updateOverrideGraph()
    OngoingNotification.refresh()
}

fun MainEngine.processNSTemporaryTarget(entries: List<JsonObject>) {
    val result = mutableListOf<TempTargetSpan>()
    var activeTempTarget: Int? = null
    var activeTempTargetEndAt: Double? = null

    for (currentEntry in entries.asReversed()) {
        val dateStr = currentEntry.string("timestamp") ?: currentEntry.string("created_at") ?: continue
        val parsedDate = NightscoutUtils.parseDate(dateStr) ?: continue

        val graphHours = 24 * Storage.downloadDays.value
        var dateTimeStamp = parsedDate
        if (dateTimeStamp < DateTimeUtils.nHoursAgo(graphHours)) dateTimeStamp = DateTimeUtils.nHoursAgo(graphHours)

        val duration = (currentEntry.double("duration") ?: 5.0) * 60

        // A duration of 0 marks the end of the last temp target.
        if (duration == 0.0) {
            val activeIndex = result.indexOfLast { it.endDate > dateTimeStamp }
            if (activeIndex >= 0) {
                result[activeIndex] = result[activeIndex].copy(endDate = dateTimeStamp)
                activeTempTarget = null
                activeTempTargetEndAt = null
            }
            continue
        }
        if (duration < 300) continue

        val reason = currentEntry.string("reason") ?: ""
        val enteredBy = currentEntry.string("enteredBy") ?: continue
        val targetValue = currentEntry.double("targetBottom") ?: currentEntry.double("targetTop") ?: continue

        val endDate = dateTimeStamp + duration
        val span = TempTargetSpan(dateTimeStamp, endDate, duration, listOf(targetValue.toInt()), enteredBy, reason, Treatment.firstEntry(currentEntry, parsedDate))
        result.add(span)

        if (nowSeconds() < endDate) {
            activeTempTarget = targetValue.toInt()
            activeTempTargetEndAt = span.scheduledEndDate
        }
    }
    tempTargetGraphData = result

    if (Storage.graphOtherTreatments.value) updateTempTargetGraph()

    Observable.tempTarget.value = activeTempTarget?.toDouble()
    Observable.tempTargetEndAt.value = activeTempTargetEndAt
    OngoingNotification.refresh()
}

fun MainEngine.processNotes(entries: List<JsonObject>) {
    val notes = mutableListOf<NotePoint>()
    val states = mutableListOf<CGMSensorState>()
    var lastFoundIndex = 0

    for (entry in entries.asReversed()) {
        val date = NightscoutUtils.treatmentDate(entry) ?: continue
        val thisNote = entry.string("notes") ?: continue
        if (date >= nowSeconds() + 60 * 60) continue

        val state = CGMSensorState.fromNote(thisNote, date)
        if (state != null) {
            states.add(state)
            continue
        }
        val (sgv, found) = findNearestBGbyTime(date, bgData, lastFoundIndex)
        lastFoundIndex = found
        notes.add(NotePoint(date, sgv.toInt(), thisNote))
    }
    noteGraphData = notes
    cgmSensorStates = states

    if (Storage.graphOtherTreatments.value) updateNotes()
    updateCGMSensorState()
}

/** Publishes the CGM state reported after the newest reading, if any. */
fun MainEngine.updateCGMSensorState() {
    val active = CGMSensorState.active(cgmSensorStates, bgData.lastOrNull()?.date)
    if (active == Observable.cgmSensorState.value) return
    Observable.cgmSensorState.value = active
    if (active != null) {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "CGM sensor state: ${active.name} at ${active.date}")
    } else {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "CGM sensor state cleared")
    }
    OngoingNotification.refresh()
}

// MARK: - Sensor, cannula and insulin age

private fun MainEngine.processSage(entries: List<String>) {
    when {
        entries.isNotEmpty() -> updateSage(entries)
        currentSage != null -> updateSage(listOf(currentSage!!))
        else -> webLoadAge(EventType.SAGE, days = 60) { updateSage(it) }
    }
}

fun MainEngine.processSensorStart(entries: List<String>) {
    val result = mutableListOf<TimestampPoint>()
    var lastFoundIndex = 0
    for (createdAt in entries) {
        val date = NightscoutUtils.parseDate(createdAt) ?: continue
        val (sgv, found) = findNearestBGbyTime(date, bgData, lastFoundIndex)
        lastFoundIndex = found
        if (date < nowSeconds() + 60 * 60) result.add(TimestampPoint(date, sgv.toInt()))
    }
    sensorStartGraphData = result
    if (Storage.graphOtherTreatments.value) updateSensorStart()
}

private fun MainEngine.processCage(entries: List<String>) {
    when {
        entries.isNotEmpty() -> updateCage(entries)
        currentCage != null -> updateCage(listOf(currentCage!!))
        else -> webLoadAge(EventType.CAGE, days = null) { updateCage(it) }
    }
}

private fun MainEngine.processIage(entries: List<String>) {
    when {
        entries.isNotEmpty() -> updateIage(entries)
        currentIage != null -> updateIage(listOf(currentIage!!))
        Storage.infoDisplayItems.value.isVisible(InfoType.IAGE) -> webLoadAge(EventType.IAGE, days = 60) { updateIage(it) }
    }
}

/** Fetches the newest treatment of an age event type ([days] back, or without a lower bound). */
private fun MainEngine.webLoadAge(eventType: EventType, days: Int?, onResult: (List<String>) -> Unit) {
    val parameters = buildMap {
        put("find[eventType]", eventType.raw)
        if (days != null) put("find[created_at][\$gte]", DateTimeUtils.dateTimeString(addingDays = -days))
        put("find[created_at][\$lte]", DateTimeUtils.dateTimeString())
        put("count", "1")
    }
    scope.launch {
        try {
            val list = NightscoutUtils.executeRequest(eventType, parameters).objectList().mapNotNull { it.string("created_at") }
            onResult(list)
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "webLoadNS${eventType.name}, error: ${e.message}")
        }
    }
}

fun MainEngine.updateSage(data: List<String>) {
    infoManager.clear(InfoType.SAGE)
    val createdAt = data.firstOrNull() ?: return
    currentSage = createdAt
    val insertTime = NightscoutUtils.parseISO(createdAt)
    if (insertTime == null) {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "updateSage: unparseable created_at '$createdAt'")
        return
    }
    Storage.sageInsertTime.value = insertTime

    // Auto-snooze all alarms for the first 2 hours after a sensor start.
    val config = Storage.alarmConfiguration.value
    if (config.autoSnoozeCGMStart && nowSeconds() - insertTime < 7200) {
        Storage.alarmConfiguration.value = config.copy(snoozeUntil = insertTime + 7200)
    }

    val secondsAgo = nowSeconds() - insertTime
    infoManager.update(InfoType.SAGE, positionalDaysHours(secondsAgo), secondsAgo / 86400)
}

fun MainEngine.updateCage(data: List<String>) {
    infoManager.clear(InfoType.CAGE)
    val createdAt = data.firstOrNull() ?: return
    currentCage = createdAt
    val insertTime = NightscoutUtils.parseISO(createdAt)
    if (insertTime == null) {
        LogManager.log(LogManager.Category.NIGHTSCOUT, "updateCage: unparseable created_at '$createdAt'")
        return
    }
    Storage.cageInsertTime.value = insertTime
    val secondsAgo = nowSeconds() - insertTime
    infoManager.update(InfoType.CAGE, positionalDaysHours(secondsAgo), secondsAgo / 86400)
}

fun MainEngine.updateIage(data: List<String>) {
    infoManager.clear(InfoType.IAGE)
    val createdAt = data.firstOrNull() ?: return
    currentIage = createdAt
    val insertTime = NightscoutUtils.parseISO(createdAt) ?: return
    Storage.iageInsertTime.value = insertTime
    val secondsAgo = nowSeconds() - insertTime
    infoManager.update(InfoType.IAGE, positionalDaysHours(secondsAgo), secondsAgo / 86400)
}

/** Periods when the pump delivers no basal, from Trio's PumpSuspend and PumpResume notes. */
object PumpSuspension {
    const val SUSPEND_NOTE = "PumpSuspend"
    const val RESUME_NOTE = "PumpResume"

    /**
     * Pairs each suspend with the next resume. A suspend without a resume lasts until
     * the next temp basal starts; without either it is still ongoing.
     */
    fun intervals(notes: List<Pair<Double, String>>, tempBasalStarts: List<Double>): List<ClosedRange<Double>> {
        val events = notes.filter { it.second == SUSPEND_NOTE || it.second == RESUME_NOTE }.sortedBy { it.first }
        val tempStarts = tempBasalStarts.sorted()
        val result = mutableListOf<ClosedRange<Double>>()
        var suspendStart: Double? = null

        fun close(resume: Double?) {
            val start = suspendStart ?: return
            val nextTemp = tempStarts.firstOrNull { it > start }
            val end = listOfNotNull(resume, nextTemp).minOrNull() ?: Double.MAX_VALUE
            if (end > start) result.add(start..end)
            suspendStart = null
        }

        for ((date, note) in events) {
            if (note == SUSPEND_NOTE) {
                val start = suspendStart
                if (start != null) {
                    val nextTemp = tempStarts.firstOrNull { it > start }
                    if (nextTemp != null && nextTemp <= date) close(null)
                }
                if (suspendStart == null) suspendStart = date
            } else {
                close(date)
            }
        }
        close(null)
        return result
    }

    /** The basal steps with the rate set to 0 inside the intervals. */
    fun applying(intervals: List<ClosedRange<Double>>, basal: List<BasalGraphPoint>): List<BasalGraphPoint> {
        if (intervals.isEmpty()) return basal
        val points = basal.sortedBy { it.date }
        val suspensions = intervals.sortedBy { it.start }
        val result = mutableListOf<BasalGraphPoint>()

        for (index in points.indices) {
            if (index + 1 >= points.size) break
            val point = points[index]
            val end = points[index + 1].date
            if (end <= point.date) continue

            var cursor = point.date
            for (suspension in suspensions) {
                val suspendStart = maxOf(suspension.start, cursor)
                val suspendEnd = minOf(suspension.endInclusive, end)
                if (suspendStart >= suspendEnd) continue
                if (cursor < suspendStart) {
                    result.add(BasalGraphPoint(point.basalRate, cursor))
                    result.add(BasalGraphPoint(point.basalRate, suspendStart))
                }
                result.add(BasalGraphPoint(0.0, suspendStart))
                result.add(BasalGraphPoint(0.0, suspendEnd))
                cursor = suspendEnd
            }
            if (cursor < end) {
                result.add(BasalGraphPoint(point.basalRate, cursor))
                result.add(BasalGraphPoint(point.basalRate, end))
            }
        }
        points.lastOrNull()?.let { last ->
            val suspended = suspensions.any { it.start <= last.date && last.date < it.endInclusive }
            result.add(BasalGraphPoint(if (suspended) 0.0 else last.basalRate, last.date))
        }
        return result
    }
}
