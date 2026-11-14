package com.loopandlearn.loopfollow.engine

import kotlinx.serialization.Serializable

/** What is known about one pump's reservoir, carried between device status records. */
@Serializable
data class PumpReservoirCache(
    val pumpID: String,
    /** When this pump first appeared in a device status record LoopFollow fetched (epoch s). */
    val pumpSince: Double,
    val reading: Reading? = null,
    /** Whether a volume in a record is this pump's own. */
    val isSettled: Boolean = true,
) {
    @Serializable
    data class Reading(val units: Double, val date: Double)
}

/** What a device status record says about the reservoir. */
sealed class PumpReservoirState {
    /** An exact volume, from the record itself or from a recent reading for the same pump. */
    data class Units(val units: Double) : PumpReservoirState()
    /** A pump that reports a volume only once it drops below 50U, and is above it. */
    data object AboveReportingLimit : PumpReservoirState()
    /** The status the uploader shows in place of the volume, such as "No Pod". */
    data class Status(val status: String) : PumpReservoirState()
    /** No volume to show. */
    data object Unknown : PumpReservoirState()
}

object PumpReservoirResolver {
    /** Omnipod reports the reservoir in only some records below 50U; a reading carries this long. */
    const val MAX_READING_AGE = 30 * 60.0

    /** The previous pod's final volume can appear for up to this long after a pod change. */
    const val PUMP_SETTLE_TIME = 15 * 60.0

    data class Resolution(val state: PumpReservoirState, val cache: PumpReservoirCache?)

    fun resolve(
        reservoir: Double?,
        pumpID: String?,
        manufacturer: String?,
        model: String?,
        displayOverride: String? = null,
        cache: PumpReservoirCache?,
        date: Double,
    ): Resolution {
        val resolution = resolveVolume(reservoir, pumpID, manufacturer, model, cache, date)
        val status = displayOverride?.trim()
        if (!status.isNullOrEmpty()) {
            return Resolution(PumpReservoirState.Status(status), resolution.cache)
        }
        return resolution
    }

    private fun resolveVolume(
        reservoir: Double?,
        rawPumpID: String?,
        manufacturer: String?,
        model: String?,
        storedCache: PumpReservoirCache?,
        date: Double,
    ): Resolution {
        val onlyWhenLow = reportsVolumeOnlyWhenLow(manufacturer, model)
        val withoutReading = if (onlyWhenLow) PumpReservoirState.AboveReportingLimit else PumpReservoirState.Unknown

        val pumpID = rawPumpID?.trim() ?: ""

        if (pumpID == "Unknown" && onlyWhenLow) {
            return Resolution(PumpReservoirState.Unknown, storedCache)
        }

        if (pumpID.isEmpty() || pumpID == "Unknown") {
            reservoir ?: return Resolution(withoutReading, storedCache)
            return Resolution(PumpReservoirState.Units(reservoir), storedCache)
        }

        var cache = if (storedCache != null && storedCache.pumpID == pumpID) {
            storedCache
        } else {
            PumpReservoirCache(pumpID, date, null, storedCache == null || !onlyWhenLow)
        }

        if (!cache.isSettled && (reservoir == null || date - cache.pumpSince >= PUMP_SETTLE_TIME)) {
            cache = cache.copy(isSettled = true)
        }

        if (reservoir != null) {
            if (!cache.isSettled) return Resolution(withoutReading, cache)
            cache = cache.copy(reading = PumpReservoirCache.Reading(reservoir, date))
            return Resolution(PumpReservoirState.Units(reservoir), cache)
        }

        val reading = cache.reading
        if (reading != null) {
            val age = date - reading.date
            if (age >= 0 && age <= MAX_READING_AGE) {
                return Resolution(PumpReservoirState.Units(reading.units), cache)
            }
            return Resolution(PumpReservoirState.Unknown, cache)
        }

        return Resolution(withoutReading, cache)
    }

    private fun reportsVolumeOnlyWhenLow(manufacturer: String?, model: String?): Boolean {
        val pump = listOfNotNull(manufacturer, model).joinToString(" ").lowercase()
        if (pump.isEmpty()) return true
        return pump.contains("insulet") || pump.contains("omnipod") || pump.contains("dash")
    }
}
