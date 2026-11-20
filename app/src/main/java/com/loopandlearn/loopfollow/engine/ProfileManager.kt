package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.array
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.doubles
import com.loopandlearn.loopfollow.nightscout.int
import com.loopandlearn.loopfollow.nightscout.obj
import com.loopandlearn.loopfollow.nightscout.objects
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.nightscout.strings
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfiles
import com.loopandlearn.loopfollow.helpers.LogManager
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import java.util.TimeZone

/** A Nightscout profile document, read leniently from its JSON. */
class NSProfile(val json: JsonObject) {
    class ScheduleEntry(val value: Double, val time: String, val timeAsSeconds: Double)

    class Store(json: JsonObject) {
        val basal = entries(json, "basal")
        val sens = entries(json, "sens")
        val carbratio = entries(json, "carbratio")
        val targetHigh: List<ScheduleEntry>? = if (json.containsKey("target_high")) entries(json, "target_high") else null
        val targetLow: List<ScheduleEntry>? = if (json.containsKey("target_low")) entries(json, "target_low") else null
        val timezone: String = json.string("timezone") ?: ""
        val units: String = json.string("units") ?: ""

        private fun entries(json: JsonObject, key: String): List<ScheduleEntry> =
            json.array(key)?.objects()?.mapNotNull { e ->
                val value = e.double("value") ?: e.string("value")?.toDoubleOrNull() ?: return@mapNotNull null
                val time = e.string("time") ?: "00:00"
                val seconds = e.double("timeAsSeconds") ?: e.string("timeAsSeconds")?.toDoubleOrNull()
                    ?: timeToSeconds(time)
                ScheduleEntry(value, time, seconds)
            } ?: emptyList()

        private fun timeToSeconds(time: String): Double {
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return (h * 3600 + m * 60).toDouble()
        }
    }

    class LoopOverridePreset(val name: String, val duration: Int?, val targetRange: List<Double>?, val insulinNeedsScaleFactor: Double?, val symbol: String?)

    class TrioOverrideEntry(val name: String, val duration: Double?, val percentage: Double?, val target: Double?)

    val store: Map<String, Store> = json.obj("store")?.mapNotNull { (k, v) ->
        (v as? JsonObject)?.let { k to Store(it) }
    }?.toMap() ?: emptyMap()
    val defaultProfile: String = json.string("defaultProfile") ?: ""
    /** The document's glucose units; mg/dL when it states none. */
    val units: String = json.string("units") ?: "mg/dL"
    val bundleIdentifier: String? = json.string("bundleIdentifier")
    val isAPNSProduction: Boolean? = json.bool("isAPNSProduction")
    val deviceToken: String? = json.string("deviceToken")
    val teamID: String? = json.string("teamID")
    val expirationDate: String? = json.string("expirationDate")
    val startDate: String? = json.string("startDate")
    val createdAt: String? = json.string("created_at")
    val remoteCommands: List<String>? = json.array("remoteCommands")?.strings()

    /** The glucose units of a store entry: its own, or the document's. Missing when the Nightscout profile editor saved the document. */
    fun units(store: Store): String = store.units.ifBlank { units }

    val loopSettings: JsonObject? = json.obj("loopSettings")
    val loopDosingEnabled: Boolean? = loopSettings?.bool("dosingEnabled")
    val loopDeviceToken: String? = loopSettings?.string("deviceToken")
    val loopBundleIdentifier: String? = loopSettings?.string("bundleIdentifier")
    val loopOverridePresets: List<LoopOverridePreset>? = loopSettings?.array("overridePresets")?.objects()?.mapNotNull { p ->
        val name = p.string("name") ?: return@mapNotNull null
        LoopOverridePreset(name, p.int("duration"), p.array("targetRange")?.doubles(), p.double("insulinNeedsScaleFactor"), p.string("symbol"))
    }

    /** Trio stores its override presets at the top level as "overridePresets". */
    val trioOverrides: List<TrioOverrideEntry>? = json.array("overridePresets")?.objects()?.mapNotNull { p ->
        val name = p.string("name") ?: return@mapNotNull null
        TrioOverrideEntry(name, p.double("duration"), p.double("percentage"), p.double("target"))
    }
}

object ProfileManager {
    data class TimeValue<T>(val timeAsSeconds: Int, val value: T)

    data class LoopOverride(
        val name: String,
        /** Target range in mg/dL. */
        val targetRange: List<Double>,
        val duration: Int?,
        val insulinNeedsScaleFactor: Double,
        val symbol: String,
    )

    data class TrioOverride(val name: String, val duration: Double?, val percentage: Double?, val target: Double?)

    // Glucose schedules are held in mg/dL.
    var isfSchedule: List<TimeValue<Double>> = emptyList()
    var basalSchedule: List<TimeValue<Double>> = emptyList()
    var carbRatioSchedule: List<TimeValue<Double>> = emptyList()
    var targetLowSchedule: List<TimeValue<Double>> = emptyList()
    var targetHighSchedule: List<TimeValue<Double>> = emptyList()
    var loopOverrides: List<LoopOverride> = emptyList()
    var trioOverrides: List<TrioOverride> = emptyList()
    var loopDosingEnabled: Boolean? = null
    var unitsAreMgdl = false
    var timezone: TimeZone = TimeZone.getDefault()

    private fun toMgdl(value: Double) = if (unitsAreMgdl) value else value * GlucoseConversion.MMOL_TO_MGDL

    fun loadProfile(profile: NSProfile) {
        val store = profile.store[profile.defaultProfile] ?: run {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Profile document not loaded, its active entry could not be read")
            return
        }

        unitsAreMgdl = profile.units(store).lowercase() == "mg/dl"
        timezone = getTimeZone(store.timezone)

        isfSchedule = store.sens.map { TimeValue(it.timeAsSeconds.toInt(), toMgdl(it.value)) }
        basalSchedule = store.basal.map { TimeValue(it.timeAsSeconds.toInt(), it.value) }
        carbRatioSchedule = store.carbratio.map { TimeValue(it.timeAsSeconds.toInt(), it.value) }
        targetLowSchedule = store.targetLow?.map { TimeValue(it.timeAsSeconds.toInt(), toMgdl(it.value)) } ?: emptyList()
        targetHighSchedule = store.targetHigh?.map { TimeValue(it.timeAsSeconds.toInt(), toMgdl(it.value)) } ?: emptyList()

        loopOverrides = profile.loopOverridePresets?.map { preset ->
            LoopOverride(
                preset.name,
                preset.targetRange?.map { toMgdl(it) } ?: emptyList(),
                preset.duration,
                preset.insulinNeedsScaleFactor ?: 1.0,
                preset.symbol ?: "",
            )
        } ?: emptyList()

        loopDosingEnabled = profile.loopDosingEnabled

        trioOverrides = profile.trioOverrides?.map { TrioOverride(it.name, it.duration, it.percentage, it.target) } ?: emptyList()

        Storage.deviceToken.value = profile.deviceToken ?: profile.loopDeviceToken ?: ""
        Storage.expirationDate.value = profile.expirationDate?.let { NightscoutUtils.parseDate(it)?.toLong() }
        Storage.bundleId.value = profile.bundleIdentifier ?: profile.loopBundleIdentifier ?: ""
        profile.isAPNSProduction?.let { Storage.productionEnvironment.value = it }
        Storage.teamId.value = profile.teamID ?: Storage.teamId.value ?: ""
        Storage.remoteCommands.value = profile.remoteCommands ?: emptyList()
        TherapyProfiles.update(profile)
    }

    fun currentISF(): Double? = isf(nowMillis())
    fun currentBasal(): String? = basal(nowMillis())
    fun currentCarbRatio(): Double? = carbRatio(nowMillis())
    fun currentTargetLow(): Double? = targetLow(nowMillis())
    fun currentTargetHigh(): Double? = targetHigh(nowMillis())

    fun isf(millis: Long): Double? = value(isfSchedule, millis)

    fun basal(millis: Long): String? = basalRate(millis)?.let { Localizer.formatToLocalizedString(it, 2, 0) }

    fun basalRate(millis: Long): Double? = value(basalSchedule, millis)

    fun carbRatio(millis: Long): Double? = value(carbRatioSchedule, millis)

    fun targetLow(millis: Long): Double? = value(targetLowSchedule, millis)

    fun targetHigh(millis: Long): Double? = value(targetHighSchedule, millis)

    data class BasalSegment(val start: Double, val rate: Double)

    /** Scheduled basal from [start] to [end] (epoch s), one segment per schedule entry crossed. */
    fun scheduledBasalSegments(start: Double, end: Double): List<BasalSegment> {
        if (start >= end) return emptyList()
        val firstRate = basalRate((start * 1000).toLong()) ?: return emptyList()

        val boundaries = mutableListOf<Double>()
        val cal = Calendar.getInstance(timezone)
        cal.timeInMillis = (start * 1000).toLong()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.timeInMillis / 1000.0 < end) {
            for (entry in basalSchedule) {
                val c = cal.clone() as Calendar
                c.set(Calendar.HOUR_OF_DAY, entry.timeAsSeconds / 3600)
                c.set(Calendar.MINUTE, entry.timeAsSeconds % 3600 / 60)
                c.set(Calendar.SECOND, entry.timeAsSeconds % 60)
                val boundary = c.timeInMillis / 1000.0
                if (boundary > start && boundary < end) boundaries.add(boundary)
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val segments = mutableListOf(BasalSegment(start, firstRate))
        for (boundary in boundaries.sorted()) {
            val rate = basalRate((boundary * 1000).toLong())
            if (rate != null && rate != segments.last().rate) segments.add(BasalSegment(boundary, rate))
        }
        return segments
    }

    private fun <T> value(schedule: List<TimeValue<T>>, millis: Long): T? {
        if (schedule.isEmpty()) return null
        val cal = Calendar.getInstance(timezone)
        cal.timeInMillis = millis
        val secs = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        var last: T? = null
        for (tv in schedule) {
            if (secs >= tv.timeAsSeconds) last = tv.value else break
        }
        return last
    }

    private fun getTimeZone(identifier: String): TimeZone {
        val ids = TimeZone.getAvailableIDs().toSet()
        if (identifier in ids) return TimeZone.getTimeZone(identifier)
        val adjusted = identifier.replace("ETC/", "Etc/")
        if (adjusted in ids) return TimeZone.getTimeZone(adjusted)
        // "GMT+2" and "UTC+2" are two hours ahead of GMT, as Foundation reads them.
        Regex("^(?:GMT|UTC)([+-]\\d{1,2}(?::?\\d{2})?)$").find(identifier)?.let {
            return TimeZone.getTimeZone("GMT${it.groupValues[1]}")
        }
        if (identifier.uppercase().contains("GMT")) {
            val parts = identifier.uppercase().split("GMT")
            val offsetHours = parts.getOrNull(1)?.toIntOrNull()
            if (offsetHours != null) {
                val corrected = -offsetHours
                return TimeZone.getTimeZone(String.format("GMT%+03d:00", corrected))
            }
        }
        return TimeZone.getDefault()
    }

    private fun nowMillis() = System.currentTimeMillis()
}
