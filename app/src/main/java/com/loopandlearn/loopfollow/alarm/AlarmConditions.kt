package com.loopandlearn.loopfollow.alarm

import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.StorageValue
import kotlin.math.abs
import kotlin.math.ceil

abstract class AlarmCondition(val type: AlarmType) {
    /** Pure, per-alarm logic against [AlarmData]. */
    abstract fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean

    /** Title for the firing `evaluate` just reported; null uses the alarm type's name. */
    open val firedTitle: String? get() = null

    /** True when belowBG/aboveBG are this condition's own trigger threshold. */
    open val checksOwnBGLimits: Boolean get() = false

    /** False blocks the alarm on its BG activation limits. */
    fun passesBGLimits(alarm: Alarm, data: AlarmData): Boolean {
        val bgReading = data.bgReadings.lastOrNull()?.sgv
        val haveBG = (bgReading ?: 0) > 0
        val bg = (bgReading ?: 0).toDouble()

        if (alarm.type.isBGBased && !haveBG) return false
        if (checksOwnBGLimits) return true
        if (alarm.belowBG == null && alarm.aboveBG == null) return true
        if (!haveBG) return false

        val lo = alarm.belowBG
        val hi = alarm.aboveBG
        return when {
            lo != null && hi != null -> if (lo < hi) (bg <= lo || bg >= hi) else (hi <= bg && bg <= lo)
            lo != null -> bg <= lo
            hi != null -> bg >= hi
            else -> true
        }
    }

    /** Applies every global and per-alarm guard, then the type-specific logic. */
    fun shouldFire(alarm: Alarm, data: AlarmData, now: Double, config: AlarmConfiguration): Boolean {
        if (!alarm.isEnabled) return false
        val snooze = alarm.snoozedUntil
        if (snooze != null && snooze > now) return false
        if (!passesBGLimits(alarm, data)) return false

        val isNight = config.isNight(now)
        when (alarm.activeOption) {
            DayNightOption.DAY -> if (isNight) return false
            DayNightOption.NIGHT -> if (!isNight) return false
            else -> {}
        }
        return evaluate(alarm, data, now)
    }
}

class BatteryCondition : AlarmCondition(AlarmType.BATTERY) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val limit = alarm.threshold?.takeIf { it > 0 } ?: return false
        val level = data.latestBattery ?: return false
        if (alarm.suppressIfCharging && data.latestBatteryIsCharging == true) return false
        return level <= limit
    }
}

/** Fires when the phone battery falls by ≥ delta % within monitoringWindow minutes. */
class BatteryDropCondition : AlarmCondition(AlarmType.BATTERY_DROP) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val drop = alarm.delta?.takeIf { it > 0 } ?: return false
        val minutes = alarm.monitoringWindow?.takeIf { it > 0 } ?: return false
        val latest = data.batteryHistory.lastOrNull() ?: return false
        val target = latest.timestamp - minutes * 60.0
        val earlier = data.batteryHistory.minByOrNull { abs(it.timestamp - target) } ?: return false
        // A drop from 100 % is a false drop when just unplugged.
        if (earlier.batteryLevel >= 100) return false
        return earlier.batteryLevel - latest.batteryLevel >= drop
    }
}

class BuildExpireCondition : AlarmCondition(AlarmType.BUILD_EXPIRE) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val expiry = data.expireDate ?: return false
        val days = alarm.threshold ?: return false
        return now >= expiry - days * TimeUnit.DAY.seconds
    }
}

class COBCondition : AlarmCondition(AlarmType.COB) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val threshold = alarm.threshold?.takeIf { it > 0 } ?: return false
        val cob = data.cob
        if (cob == null || cob < threshold) {
            Storage.lastCOBNotified.value = null
            return false
        }
        val last = Storage.lastCOBNotified.value
        if (last != null && cob <= last) return false
        Storage.lastCOBNotified.value = cob
        return true
    }
}

/** Fires when the Nightscout database has filled ≥ threshold percent of its limit. */
class DBSizeCondition : AlarmCondition(AlarmType.DB_SIZE) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val limit = alarm.threshold?.takeIf { it > 0 } ?: return false
        val used = data.dbSizePercentage ?: return false
        return used >= limit
    }
}

enum class EndAlarmPhase { ENDED, ENDING_SOON }

/** Two-phase evaluation shared by the temp target and override end alarms. */
class EndAlarmPhases(
    private val latestStart: Double?,
    private val latestEnd: Double?,
    private val activeEnd: Double?,
    private val leadMinutes: Int?,
    private val endedMarker: StorageValue<Double?>,
    private val warnedMarker: StorageValue<Double?>,
) {
    fun fire(now: Double): EndAlarmPhase? {
        val end = latestEnd
        if (end != null && end > 0 && now - end <= ENDED_GRACE && end > (endedMarker.value ?: 0.0)) {
            endedMarker.value = end
            return EndAlarmPhase.ENDED
        }
        val lead = leadMinutes?.takeIf { it > 0 } ?: return null
        val endTS = activeEnd ?: return null
        val opensAt = endTS - lead * 60.0
        // An event shorter than the lead time is covered by its start alarm.
        val start = latestStart
        if (start != null && opensAt <= start) return null
        if (now < opensAt || warnedMarker.value == endTS) return null
        warnedMarker.value = endTS
        return EndAlarmPhase.ENDING_SOON
    }

    companion object {
        const val ENDED_GRACE = 15 * 60.0
    }
}

class FastDropCondition : AlarmCondition(AlarmType.FAST_DROP) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val dropPerReading = alarm.delta?.takeIf { it > 0 } ?: return false
        val needed = alarm.monitoringWindow?.takeIf { it > 0 } ?: return false
        if (data.bgReadings.size < needed + 1) return false
        val readings = data.bgReadings.takeLast(needed + 1)
        for (i in 1..needed) {
            if ((readings[i - 1].sgv - readings[i].sgv).toDouble() < dropPerReading) return false
        }
        return true
    }
}

/** Fires when N consecutive BG deltas are ≥ delta mg/dL. */
class FastRiseCondition : AlarmCondition(AlarmType.FAST_RISE) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val rise = alarm.delta?.takeIf { it > 0 } ?: return false
        val streak = alarm.monitoringWindow?.takeIf { it > 0 } ?: return false
        if (data.bgReadings.size < streak + 1) return false
        val recent = data.bgReadings.takeLast(streak + 1).map { it.sgv }
        return recent.zipWithNext().all { (a, b) -> (b - a).toDouble() >= rise }
    }
}

/** Fires once when a future-dated carb entry's scheduled time arrives. */
class FutureCarbsCondition : AlarmCondition(AlarmType.FUTURE_CARBS) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val maxLookaheadSec = (alarm.threshold ?: 45.0) * 60
        val minGrams = alarm.delta ?: 5.0
        val tolerance = 5.0
        val pending = Storage.pendingFutureCarbs.value.toMutableList()

        // Track all future carbs, so ones outside the window can't drift in with a fresh observedAt.
        for (carb in data.recentCarbs) {
            if (carb.date <= now || carb.grams < minGrams) continue
            val tracked = pending.any { abs(it.carbDate - carb.date) < tolerance && it.grams == carb.grams }
            if (!tracked) pending.add(PendingFutureCarb(carb.date, carb.grams, now))
        }

        var fired = false
        pending.removeAll { entry ->
            val stillExists = data.recentCarbs.any { abs(it.date - entry.carbDate) < tolerance && it.grams == entry.grams }
            when {
                now - entry.observedAt > 7200 && !stillExists -> true
                entry.carbDate > now -> false
                !stillExists -> true
                entry.carbDate - entry.observedAt > maxLookaheadSec -> true
                !fired -> {
                    fired = true
                    true
                }
                else -> false
            }
        }
        Storage.pendingFutureCarbs.value = pending
        return fired
    }
}

/** Fires when the latest BG — and, if requested, every BG in a persistent window — is ≥ aboveBG. */
class HighBGCondition : AlarmCondition(AlarmType.HIGH) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val high = alarm.aboveBG ?: return false
        fun isHigh(g: GlucoseValue) = g.sgv > 0 && g.sgv >= high

        var persistentOK = true
        val persistentMinutes = alarm.persistentMinutes
        if (persistentMinutes != null && persistentMinutes > 0) {
            val window = ceil(persistentMinutes / 5.0).toInt()
            persistentOK = data.bgReadings.size >= window && data.bgReadings.takeLast(window).all(::isHigh)
        }

        if (alarm.suppressIfFalling && data.bgReadings.size >= 2) {
            val last = data.bgReadings[data.bgReadings.size - 1]
            val previous = data.bgReadings[data.bgReadings.size - 2]
            if (last.sgv > 0 && previous.sgv > 0 && last.sgv < previous.sgv) return false
        }
        return persistentOK
    }
}

/**
 * Fires when the latest IOB ≥ threshold, or within the look-back window the count
 * of boluses ≥ delta units reaches monitoringWindow, or their sum reaches threshold.
 */
class IOBCondition : AlarmCondition(AlarmType.IOB) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val iobMax = alarm.threshold?.takeIf { it > 0 } ?: return false
        val minBolus = alarm.delta?.takeIf { it > 0 } ?: return false
        val countNeeded = alarm.monitoringWindow?.takeIf { it > 0 } ?: return false
        val lookback = alarm.predictiveMinutes?.takeIf { it > 0 } ?: return false

        val iob = data.iob
        if (iob != null && iob >= iobMax) return true

        val cutoff = now - lookback * 60.0
        var count = 0
        var total = 0.0
        for (b in data.recentBoluses) {
            if (b.date < cutoff || b.units < minBolus) continue
            count += 1
            total += b.units
            if (count >= countNeeded || total >= iobMax) return true
        }
        return false
    }
}

/** Fires when the latest (and persistent) BG is ≤ belowBG, or any predicted BG within predictiveMinutes is. */
class LowBGCondition : AlarmCondition(AlarmType.LOW) {
    /** The predictive look-ahead must run while the latest BG is still above the limit. */
    override val checksOwnBGLimits: Boolean get() = true

    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val belowBG = alarm.belowBG ?: return false
        val latest = data.bgReadings.lastOrNull()?.takeIf { it.sgv > 0 } ?: return false
        fun isLow(g: GlucoseValue) = g.sgv > 0 && g.sgv <= belowBG

        var predictive = false
        val predictiveMinutes = alarm.predictiveMinutes
        if (predictiveMinutes != null && predictiveMinutes > 0 && data.predictionData.isNotEmpty()) {
            predictive = data.predictionData.take(forecastPoints(predictiveMinutes)).any(::isLow)
        }

        var persistentOK = true
        val persistentMinutes = alarm.persistentMinutes
        if (persistentMinutes != null && persistentMinutes > 0) {
            val window = ceil(persistentMinutes / 5.0).toInt()
            persistentOK = data.bgReadings.size >= window && data.bgReadings.takeLast(window).all(::isLow)
        }

        if (alarm.suppressIfRising && data.bgReadings.size >= 2) {
            val last = data.bgReadings[data.bgReadings.size - 1]
            val previous = data.bgReadings[data.bgReadings.size - 2]
            if (last.sgv > 0 && previous.sgv > 0 && last.sgv > previous.sgv) return false
        }
        return (isLow(latest) && persistentOK) || predictive
    }

    companion object {
        /** Longest predictive look-ahead offered by the editor, in minutes. */
        const val MAX_PREDICTIVE_MINUTES = 60

        /** Forecast points (5-minute spacing, first is now) needed to look [minutes] ahead. */
        fun forecastPoints(minutes: Int): Int = ceil(minutes / 5.0).toInt() + 1
    }
}

/** Fires once per carb entry when no qualifying bolus follows within the delay. */
class MissedBolusCondition : AlarmCondition(AlarmType.MISSED_BOLUS) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val delayMin = alarm.monitoringWindow?.takeIf { it > 0 } ?: return false
        val prebolusMin = alarm.predictiveMinutes ?: return false
        val minBolusU = alarm.delta ?: return false
        val minCarbGr = alarm.threshold ?: return false

        val carb = data.recentCarbs.lastOrNull { it.date <= now } ?: return false
        if (!(carb.date > now - 3600 && carb.date < now - delayMin * 60.0)) return false
        if (carb.grams <= minCarbGr) return false

        val lastFired = Storage.lastMissedBolusNotified.value
        if (lastFired != null && carb.date <= lastFired) return false

        val windowStart = carb.date - prebolusMin * 60.0
        if (data.recentBoluses.any { it.date >= windowStart && it.units > minBolusU }) return false

        Storage.lastMissedBolusNotified.value = carb.date
        return true
    }
}

/** Fires when the newest CGM reading is older than threshold minutes. */
class MissedReadingCondition : AlarmCondition(AlarmType.MISSED_READING) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val threshold = alarm.threshold?.takeIf { it > 0 } ?: return false
        val last = data.bgReadings.lastOrNull() ?: return false
        val lastChecked = Storage.lastBGChecked.value ?: return false
        // A stale check means the data is unreliable.
        if (now - lastChecked / 1000.0 > 360) return false
        return now - last.date >= threshold * 60
    }
}

class NotLoopingCondition : AlarmCondition(AlarmType.NOT_LOOPING) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val threshold = alarm.threshold?.takeIf { it > 0 } ?: return false
        val lastLoop = data.lastLoopTime?.takeIf { it > 0 } ?: return false
        val lastChecked = Storage.lastLoopingChecked.value ?: return false
        if (now - lastChecked / 1000.0 > 360) return false
        return now - lastLoop >= threshold * 60
    }
}

class OverrideEndCondition : AlarmCondition(AlarmType.OVERRIDE_END) {
    private var title: String? = null
    override val firedTitle: String? get() = title

    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val phase = EndAlarmPhases(
            data.latestOverrideStart, data.latestOverrideEnd, data.activeOverrideEnd, alarm.predictiveMinutes,
            Storage.lastOverrideEndNotified, Storage.lastOverridePreEndNotified,
        ).fire(now)
        title = if (phase == EndAlarmPhase.ENDING_SOON) "Override Ending Soon" else null
        return phase != null
    }
}

class OverrideStartCondition : AlarmCondition(AlarmType.OVERRIDE_START) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val start = data.latestOverrideStart?.takeIf { it > 0 } ?: return false
        if (now - start > 15 * 60) return false
        if (start <= (Storage.lastOverrideStartNotified.value ?: 0.0)) return false
        Storage.lastOverrideStartNotified.value = start
        return true
    }
}

class PumpBatteryCondition : AlarmCondition(AlarmType.PUMP_BATTERY) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val limit = alarm.threshold?.takeIf { it > 0 } ?: return false
        val level = data.latestPumpBattery ?: return false
        return level <= limit
    }
}

/** Fires when ≤ threshold hours remain of the 3-day pod/cannula lifetime. */
class PumpChangeCondition : AlarmCondition(AlarmType.PUMP_CHANGE) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val warnAheadHrs = alarm.threshold?.takeIf { it > 0 } ?: return false
        val insert = data.pumpInsertTime?.takeIf { it > 0 } ?: return false
        return now >= insert + 3 * 24 * 3600 - warnAheadHrs * 3600
    }
}

class PumpVolumeCondition : AlarmCondition(AlarmType.PUMP) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val threshold = alarm.threshold?.takeIf { it > 0 } ?: return false
        val volume = data.latestPumpVolume ?: return false
        return volume <= threshold
    }
}

/** Fires when the recommended bolus reaches the threshold, again after a 5 % increase. */
class RecBolusCondition : AlarmCondition(AlarmType.REC_BOLUS) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val threshold = alarm.threshold?.takeIf { it > 0 } ?: return false
        val rec = data.recBolus
        if (rec == null || rec < threshold) {
            Storage.lastRecBolusNotified.value = null
            return false
        }
        val last = Storage.lastRecBolusNotified.value
        val shouldAlert = last == null || rec > last * 1.05
        Storage.lastRecBolusNotified.value = rec
        return shouldAlert
    }
}

/** Fires when ≤ threshold hours remain of the configured sensor lifetime. */
class SensorAgeCondition : AlarmCondition(AlarmType.SENSOR_CHANGE) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val warnAheadHrs = alarm.threshold?.takeIf { it > 0 } ?: return false
        val insert = data.sageInsertTime?.takeIf { it > 0 } ?: return false
        val lifetime = (alarm.sensorLifetimeDays ?: 10) * 24 * 3600.0
        return now >= insert + lifetime - warnAheadHrs * 3600
    }
}

/** A single-fire BG-limit alarm; the limits are checked by passesBGLimits. */
class TemporaryCondition : AlarmCondition(AlarmType.TEMPORARY) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean =
        alarm.belowBG != null || alarm.aboveBG != null
}

class TempTargetEndCondition : AlarmCondition(AlarmType.TEMP_TARGET_END) {
    private var title: String? = null
    override val firedTitle: String? get() = title

    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val phase = EndAlarmPhases(
            data.latestTempTargetStart, data.latestTempTargetEnd, data.activeTempTargetEnd, alarm.predictiveMinutes,
            Storage.lastTempTargetEndNotified, Storage.lastTempTargetPreEndNotified,
        ).fire(now)
        title = if (phase == EndAlarmPhase.ENDING_SOON) "Temp Target Ending Soon" else null
        return phase != null
    }
}

class TempTargetStartCondition : AlarmCondition(AlarmType.TEMP_TARGET_START) {
    override fun evaluate(alarm: Alarm, data: AlarmData, now: Double): Boolean {
        val start = data.latestTempTargetStart?.takeIf { it > 0 } ?: return false
        if (now - start > 15 * 60) return false
        if (start <= (Storage.lastTempTargetStartNotified.value ?: 0.0)) return false
        Storage.lastTempTargetStartNotified.value = start
        return true
    }
}
