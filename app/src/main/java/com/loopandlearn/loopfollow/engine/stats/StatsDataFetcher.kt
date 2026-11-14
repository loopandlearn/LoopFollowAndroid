package com.loopandlearn.loopfollow.engine.stats

import com.loopandlearn.loopfollow.engine.BasalGraphPoint
import com.loopandlearn.loopfollow.engine.BasalProfileEntry
import com.loopandlearn.loopfollow.engine.BolusGraphPoint
import com.loopandlearn.loopfollow.engine.CarbGraphPoint
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.NSProfile
import com.loopandlearn.loopfollow.engine.ShareGlucoseData
import com.loopandlearn.loopfollow.engine.deduplicateBGReadings
import com.loopandlearn.loopfollow.engine.findNearestBGbyTime
import com.loopandlearn.loopfollow.engine.findNearestBolusbyTime
import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.int
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/** Downloads BG and treatments for the selected range into [StatsStore]. */
class StatsDataFetcher(private val dataService: StatsDataService) {
    suspend fun fetchBGData(days: Int) {
        if (!MainEngine.isNightscoutEnabled()) return

        val parameters = mapOf(
            "count" to "${days * Globals.MAX_EXPECTED_UPLOADERS * 24 * 60 / 5}",
            "find[dateString][\$gte]" to NightscoutUtils.formatISO(dataService.startDate),
            "find[type][\$ne]" to "cal",
        )

        val entries = try {
            NightscoutUtils.executeRequest(EventType.SGV, parameters).objectList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Failed to fetch stats BG data: $e", limitIdentifier = "Failed to fetch stats BG data")
            return
        }

        val nsData = entries.mapNotNull { e ->
            val value = e.double("sgv") ?: e.double("mbg") ?: e.double("glucose") ?: return@mapNotNull null
            val date = e.double("date") ?: return@mapNotNull null
            ShareGlucoseData(value.roundToInt(), Math.rint(date / 1000), e.string("direction"))
        }
        val nsData2 = deduplicateBGReadings(nsData)

        val cutoffTime = dataService.startDate
        StatsStore.bgData.removeAll { it.date < cutoffTime }

        val existingDates = StatsStore.bgData.map { it.date.toInt() }.toHashSet()
        for (reading in nsData2) {
            if (reading.date.toInt() !in existingDates && reading.date >= cutoffTime) {
                StatsStore.bgData.add(reading)
            }
        }
        StatsStore.bgData.sortBy { it.date }
    }

    suspend fun fetchTreatmentsData(days: Int) {
        if (!MainEngine.isNightscoutEnabled() || !Storage.downloadTreatments.value) return
        ensureBasalProfileLoaded()
        fetchAndMergeTreatments(days)
    }

    private suspend fun ensureBasalProfileLoaded() {
        if (MainEngine.basalProfile.isNotEmpty()) return

        val parameters = mapOf(
            "count" to "1",
            "find[startDate][\$lte]" to NightscoutUtils.formatISO(nowSeconds() + 60, fractional = true),
        )
        val profiles = try {
            NightscoutUtils.executeRequest(EventType.PROFILE, parameters).objectList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Failed to fetch profile data for stats basal calculations: ${e.message}")
            return
        }
        val profileData = profiles.firstOrNull()?.let { NSProfile(it) }
        if (profileData == null) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "ensureBasalProfileLoaded, no profile records returned")
            return
        }
        val profileStore = profileData.store["default"] ?: profileData.store["Default"] ?: profileData.store[profileData.defaultProfile]
        if (profileStore != null) {
            MainEngine.basalProfile = profileStore.basal.map { BasalProfileEntry(it.value, it.time, it.timeAsSeconds) }
        }
    }

    private suspend fun fetchAndMergeTreatments(days: Int) {
        val parameters = mapOf(
            "find[created_at][\$gte]" to NightscoutUtils.formatISO(dataService.startDate, fractional = true),
            "find[created_at][\$lte]" to NightscoutUtils.formatISO(dataService.endDate, fractional = true),
            "count" to "${maxOf(days * 100, 5000)}",
        )

        val entries = try {
            NightscoutUtils.executeRequest(EventType.TREATMENTS, parameters).objectList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Failed to fetch stats treatments data: ${e.message}")
            return
        }

        mergeBolusData(entries)
        mergeSMBData(entries)
        mergeCarbData(entries)
        mergeBasalData(entries)
    }

    private fun isManualBolusType(eventType: String) =
        eventType == "Correction Bolus" || eventType == "Bolus" || eventType == "External Insulin"

    private fun mergeBolusData(entries: List<JsonObject>) {
        val cutoffTime = dataService.startDate

        val bolusEntries = entries.filter { entry ->
            when (val eventType = entry.string("eventType")) {
                null -> false
                "Meal Bolus" -> true
                else -> isManualBolusType(eventType) && entry.bool("automatic") != true
            }
        }
        mergeBoluses(bolusEntries, cutoffTime, StatsStore.bolusData)
    }

    private fun mergeSMBData(entries: List<JsonObject>) {
        val cutoffTime = dataService.startDate

        val smbEntries = entries.filter { entry ->
            when (val eventType = entry.string("eventType")) {
                null -> false
                "SMB" -> true
                else -> isManualBolusType(eventType) && entry.bool("automatic") == true
            }
        }
        mergeBoluses(smbEntries, cutoffTime, StatsStore.smbData)
    }

    private fun mergeBoluses(entries: List<JsonObject>, cutoffTime: Double, target: MutableList<BolusGraphPoint>) {
        target.removeAll { it.date < cutoffTime }

        val existingDates = target.map { it.date.toInt() }.toHashSet()
        var lastFoundIndex = 0

        for (entry in entries.asReversed()) {
            val dateTimeStamp = NightscoutUtils.treatmentDate(entry) ?: continue
            val bolus = entry.double("insulin") ?: continue
            if (dateTimeStamp < cutoffTime) continue
            if (dateTimeStamp.toInt() in existingDates) continue

            val (sgv, foundIndex) = findNearestBGbyTime(dateTimeStamp, StatsStore.bgData, lastFoundIndex)
            lastFoundIndex = foundIndex

            target.add(BolusGraphPoint(bolus, dateTimeStamp, (sgv + 20).toInt()))
        }
        target.sortBy { it.date }
    }

    private fun mergeCarbData(entries: List<JsonObject>) {
        val cutoffTime = dataService.startDate
        val now = dataService.endDate

        val carbEntries = entries.filter {
            val eventType = it.string("eventType")
            eventType == "Carb Correction" || eventType == "Meal Bolus"
        }

        StatsStore.carbData.removeAll { it.date < cutoffTime || it.date > now }

        val existingDates = StatsStore.carbData.map { it.date.toInt() }.toHashSet()
        var lastFoundIndex = 0
        var lastFoundBolus = 0

        for (entry in carbEntries.asReversed()) {
            val absorptionTime = entry.int("absorptionTime") ?: 0
            val dateTimeStamp = NightscoutUtils.treatmentDate(entry) ?: continue
            val carbs = entry.double("carbs") ?: continue

            if (dateTimeStamp < cutoffTime || dateTimeStamp > now) continue
            if (dateTimeStamp.toInt() in existingDates) continue

            val (sgv, foundIndex) = findNearestBGbyTime(dateTimeStamp, StatsStore.bgData, lastFoundIndex)
            lastFoundIndex = foundIndex

            var offset = -50
            if (sgv < (MainEngine.calculateMaxBgGraphValue() - 100).toDouble()) {
                val (hasOffset, bolusIndex) = findNearestBolusbyTime(300, dateTimeStamp, StatsStore.bolusData, lastFoundBolus)
                lastFoundBolus = bolusIndex
                offset = if (hasOffset) 70 else 20
            }

            StatsStore.carbData.add(CarbGraphPoint(carbs, dateTimeStamp, (sgv + offset).toInt(), absorptionTime))
        }
        StatsStore.carbData.sortBy { it.date }
    }

    private fun basalEntryDate(entry: JsonObject?): Double? {
        val raw = entry?.string("timestamp") ?: entry?.string("created_at") ?: return null
        return NightscoutUtils.parseDate(raw)
    }

    private fun mergeBasalData(entries: List<JsonObject>) {
        val cutoffTime = dataService.startDate
        val now = dataService.endDate
        val profile = MainEngine.basalProfile
        val basalData = StatsStore.basalData

        val tempArray = entries.filter { it.string("eventType") == "Temp Basal" }.reversed()

        basalData.removeAll { it.date < cutoffTime }

        StatsStore.tempBasalEntries.removeAll { it.startTime < cutoffTime }
        val existingTempBasalTimes = StatsStore.tempBasalEntries.map { it.startTime.toInt() }.toHashSet()

        val existingDates = basalData.map { it.date.toInt() }.toHashSet()

        fun addGap(from: Double, to: Double) {
            for (entry in createScheduledBasalEntriesForGap(from, to, profile, existingDates)) {
                if (entry.date.toInt() !in existingDates) {
                    basalData.add(entry)
                    existingDates.add(entry.date.toInt())
                }
            }
        }

        for (i in tempArray.indices) {
            val currentEntry = tempArray[i]
            val dateTimeStamp = basalEntryDate(currentEntry) ?: continue
            if (dateTimeStamp < cutoffTime) continue

            val basalRate = currentEntry.double("absolute") ?: continue
            val duration = currentEntry.double("duration") ?: 0.0

            // A temp basal ends when the next one starts.
            var effectiveDuration = duration
            if (i < tempArray.size - 1) {
                val nextDateTimeStamp = basalEntryDate(tempArray[i + 1])
                if (nextDateTimeStamp != null) {
                    val tempBasalEnd = dateTimeStamp + duration * 60
                    if (nextDateTimeStamp < tempBasalEnd) {
                        effectiveDuration = (nextDateTimeStamp - dateTimeStamp) / 60.0
                    }
                }
            }

            if (dateTimeStamp.toInt() !in existingTempBasalTimes) {
                StatsStore.tempBasalEntries.add(
                    TempBasalEntry(basalRate, dateTimeStamp, if (effectiveDuration > 0) effectiveDuration else 30.0),
                )
                existingTempBasalTimes.add(dateTimeStamp.toInt())
            }

            if (i > 0) {
                val priorEntry = tempArray[i - 1]
                val priorDateTimeStamp = basalEntryDate(priorEntry)
                if (priorDateTimeStamp != null) {
                    val priorDuration = priorEntry.double("duration") ?: 0.0
                    val priorEndTime = priorDateTimeStamp + priorDuration * 60
                    if (dateTimeStamp - priorEndTime > 15) addGap(priorEndTime, dateTimeStamp)
                }
            }

            if (dateTimeStamp.toInt() !in existingDates) {
                basalData.add(BasalGraphPoint(basalRate, dateTimeStamp))
                existingDates.add(dateTimeStamp.toInt())
            }

            var endTime = dateTimeStamp + duration * 60
            if (i == tempArray.size - 1 && duration == 0.0) {
                endTime = dateTimeStamp + 30 * 60
            }
            if (i < tempArray.size - 1) {
                val nextDateTimeStamp = basalEntryDate(tempArray[i + 1])
                if (nextDateTimeStamp != null && nextDateTimeStamp < endTime) endTime = nextDateTimeStamp
            }

            if (endTime.toInt() !in existingDates) {
                basalData.add(BasalGraphPoint(basalRate, endTime))
                existingDates.add(endTime.toInt())
            }
        }

        if (tempArray.isNotEmpty()) {
            val firstTempBasalStart = basalEntryDate(tempArray.first())
            if (firstTempBasalStart != null && firstTempBasalStart > cutoffTime) addGap(cutoffTime, firstTempBasalStart)
        } else if (profile.isNotEmpty()) {
            addGap(cutoffTime, now)
        }

        if (tempArray.isNotEmpty()) {
            val lastEntry = tempArray.last()
            val lastDuration = lastEntry.double("duration") ?: 30.0
            val lastStart = basalEntryDate(lastEntry)
            if (lastStart != null) {
                val lastTempBasalEnd = lastStart + lastDuration * 60
                if (lastTempBasalEnd < now) addGap(lastTempBasalEnd, now)
            }
        }

        basalData.sortBy { it.date }
    }

    private fun createScheduledBasalEntriesForGap(
        startTime: Double,
        endTime: Double,
        profile: List<BasalProfileEntry>,
        existingDates: Set<Int>,
    ): List<BasalGraphPoint> {
        if (profile.isEmpty() || endTime <= startTime) return emptyList()

        val entries = mutableListOf<BasalGraphPoint>()
        val sortedProfile = profile.sortedBy { it.timeAsSeconds }

        var currentTime = startTime
        while (currentTime < endTime) {
            val dayStart = StatsCalendar.startOfDay(currentTime)
            val nextDayStart = StatsCalendar.addDays(dayStart, 1)

            for (i in sortedProfile.indices) {
                val segmentRate = sortedProfile[i].value
                val segmentStartInDay = dayStart + sortedProfile[i].timeAsSeconds
                val segmentEndInDay = if (i < sortedProfile.size - 1) dayStart + sortedProfile[i + 1].timeAsSeconds else nextDayStart

                val overlapStart = maxOf(currentTime, segmentStartInDay)
                val overlapEnd = minOf(endTime, segmentEndInDay)

                if (overlapEnd > overlapStart && overlapStart.toInt() !in existingDates) {
                    entries.add(BasalGraphPoint(segmentRate, overlapStart))
                    if (overlapEnd < endTime && overlapEnd.toInt() !in existingDates) {
                        entries.add(BasalGraphPoint(segmentRate, overlapEnd))
                    }
                }
            }
            currentTime = nextDayStart
        }
        return entries
    }
}
