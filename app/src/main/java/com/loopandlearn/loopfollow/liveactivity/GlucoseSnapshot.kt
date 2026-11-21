package com.loopandlearn.loopfollow.liveactivity

import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.helpers.positionalDaysHours
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.GlucoseThresholds
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The values the Live Activity card shows, read from the same state as the home screen. */
data class GlucoseSnapshot(
    val glucose: Double,
    val delta: Double,
    val trend: Trend,
    val updatedAt: Double,
    val iob: Double?,
    val cob: Double?,
    val projected: Double?,
    val override: String?,
    val overrideEndAt: Double?,
    val tempTargetMgdl: Double?,
    val tempTargetEndAt: Double?,
    val recBolus: Double?,
    val battery: Double?,
    val pumpBattery: Double?,
    val basalRate: String,
    val pumpReservoirU: Double?,
    val autosens: Double?,
    val tdd: Double?,
    val targetLowMgdl: Double?,
    val targetHighMgdl: Double?,
    val isfMgdlPerU: Double?,
    val carbRatio: Double?,
    val carbsToday: Double?,
    val profileName: String?,
    val sageInsertTime: Double,
    val cageInsertTime: Double,
    val iageInsertTime: Double,
    val minBgMgdl: Double?,
    val maxBgMgdl: Double?,
    val unit: GlucoseDisplayUnit,
    val isNotLooping: Boolean,
    val cgmSensorState: String?,
) {
    enum class Trend {
        UP, UP_SLIGHT, UP_FAST, FLAT, DOWN, DOWN_SLIGHT, DOWN_FAST, UNKNOWN;

        companion object {
            /** Maps a Nightscout or Dexcom direction string, e.g. "FortyFiveUp". */
            fun fromCode(code: String?): Trend {
                val raw = code?.trim()?.lowercase() ?: ""
                return when {
                    raw.contains("doubleup") || raw.contains("rapidrise") || raw == "up2" || raw == "upfast" -> UP_FAST
                    raw.contains("fortyfiveup") -> UP_SLIGHT
                    raw.contains("singleup") || raw == "up" || raw == "up1" || raw == "rising" -> UP
                    raw.contains("flat") || raw == "steady" || raw == "none" -> FLAT
                    raw.contains("doubledown") || raw.contains("rapidfall") || raw == "down2" || raw == "downfast" -> DOWN_FAST
                    raw.contains("fortyfivedown") -> DOWN_SLIGHT
                    raw.contains("singledown") || raw == "down" || raw == "down1" || raw == "falling" -> DOWN
                    else -> UNKNOWN
                }
            }
        }
    }

    companion object {
        /** The current snapshot, or null while there is no reading. */
        fun current(): GlucoseSnapshot? {
            val bg = Observable.bg.value?.takeIf { it > 0 } ?: return null
            val updatedAt = Storage.lastBgReadingTimeSeconds.value ?: return null
            val lastLoopTime = Storage.lastLoopTime.value
            return GlucoseSnapshot(
                glucose = bg.toDouble(),
                delta = Storage.lastDeltaMgdl.value ?: 0.0,
                trend = Trend.fromCode(Storage.lastTrendCode.value),
                updatedAt = updatedAt,
                iob = Storage.lastIOB.value,
                cob = Storage.lastCOB.value,
                projected = Storage.projectedBgMgdl.value,
                override = Observable.override.value,
                overrideEndAt = Observable.overrideEndAt.value,
                tempTargetMgdl = Observable.tempTarget.value,
                tempTargetEndAt = Observable.tempTargetEndAt.value,
                recBolus = Observable.deviceRecBolus.value,
                battery = Observable.deviceBatteryLevel.value,
                pumpBattery = Observable.pumpBatteryLevel.value,
                basalRate = Storage.lastBasal.value,
                pumpReservoirU = Storage.lastPumpReservoirU.value,
                autosens = Storage.lastAutosens.value,
                tdd = Storage.lastTdd.value,
                targetLowMgdl = Storage.lastTargetLowMgdl.value,
                targetHighMgdl = Storage.lastTargetHighMgdl.value,
                isfMgdlPerU = Storage.lastIsfMgdlPerU.value,
                carbRatio = Storage.lastCarbRatio.value,
                carbsToday = Storage.lastCarbsToday.value,
                profileName = Storage.lastProfileName.value.ifEmpty { null },
                sageInsertTime = Storage.sageInsertTime.value,
                cageInsertTime = Storage.cageInsertTime.value,
                iageInsertTime = Storage.iageInsertTime.value,
                minBgMgdl = Storage.lastMinBgMgdl.value,
                maxBgMgdl = Storage.lastMaxBgMgdl.value,
                unit = UnitSettingsStore.glucoseUnit,
                isNotLooping = lastLoopTime > 0 && Storage.url.value.isNotEmpty() && nowSeconds() - lastLoopTime >= 15 * 60,
                cgmSensorState = Observable.cgmSensorState.value?.summary,
            )
        }
    }
}

object LAFormat {
    private fun number(fractionDigits: Int): NumberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }

    private fun fixed(value: Double, fractionDigits: Int): String = String.format(Locale.US, "%.${fractionDigits}f", value)

    fun glucoseValue(mgdl: Double, unit: GlucoseDisplayUnit): String = when (unit) {
        GlucoseDisplayUnit.MGDL -> number(0).format(mgdl.roundToInt())
        GlucoseDisplayUnit.MMOLL -> number(1).format(GlucoseConversion.toMmol(mgdl))
    }

    fun glucose(s: GlucoseSnapshot) = glucoseValue(s.glucose, s.unit)

    fun delta(s: GlucoseSnapshot): String = when (s.unit) {
        GlucoseDisplayUnit.MGDL -> {
            val v = s.delta.roundToInt()
            if (v > 0) "+$v" else "$v"
        }
        GlucoseDisplayUnit.MMOLL -> {
            val mmol = GlucoseConversion.toMmol(s.delta)
            val d = if (abs(mmol) < 0.05) 0.0 else mmol
            val formatted = number(1).format(abs(d))
            when {
                d > 0 -> "+$formatted"
                d < 0 -> "-$formatted"
                else -> formatted
            }
        }
    }

    fun trendArrow(s: GlucoseSnapshot): String = when (s.trend) {
        GlucoseSnapshot.Trend.UP_FAST -> "↑↑"
        GlucoseSnapshot.Trend.UP -> "↑"
        GlucoseSnapshot.Trend.UP_SLIGHT -> "↗"
        GlucoseSnapshot.Trend.FLAT -> "→"
        GlucoseSnapshot.Trend.DOWN_SLIGHT -> "↘︎"
        GlucoseSnapshot.Trend.DOWN -> "↓"
        GlucoseSnapshot.Trend.DOWN_FAST -> "↓↓"
        GlucoseSnapshot.Trend.UNKNOWN -> "–"
    }

    private const val NONE = "—"

    fun slotValue(option: LiveActivitySlotOption, s: GlucoseSnapshot): String = when (option) {
        LiveActivitySlotOption.NONE -> ""
        LiveActivitySlotOption.DELTA -> delta(s)
        LiveActivitySlotOption.PROJECTED_BG -> s.projected?.let { glucoseValue(it, s.unit) } ?: NONE
        LiveActivitySlotOption.MIN_MAX -> {
            val mn = s.minBgMgdl
            val mx = s.maxBgMgdl
            if (mn == null || mx == null) NONE else "${glucoseValue(mn, s.unit)}/${glucoseValue(mx, s.unit)}"
        }
        LiveActivitySlotOption.IOB -> s.iob?.let { fixed(it, 1) } ?: NONE
        LiveActivitySlotOption.COB -> s.cob?.let { "${it.roundToInt()}" } ?: NONE
        LiveActivitySlotOption.REC_BOLUS -> s.recBolus?.let { fixed(it, 2) + "U" } ?: NONE
        LiveActivitySlotOption.AUTOSENS -> s.autosens?.let { fixed(it * 100, 0) + "%" } ?: NONE
        LiveActivitySlotOption.TDD -> s.tdd?.let { fixed(it, 1) + "U" } ?: NONE
        LiveActivitySlotOption.BASAL -> s.basalRate.ifEmpty { NONE }
        LiveActivitySlotOption.PUMP -> s.pumpReservoirU?.let { "${it.roundToInt()}U" } ?: "50+U"
        LiveActivitySlotOption.PUMP_BATTERY -> s.pumpBattery?.let { fixed(it, 0) + "%" } ?: NONE
        LiveActivitySlotOption.BATTERY -> s.battery?.let { fixed(it, 0) + "%" } ?: NONE
        LiveActivitySlotOption.TARGET -> target(s)
        LiveActivitySlotOption.ISF -> s.isfMgdlPerU?.takeIf { it > 0 }?.let { glucoseValue(it, s.unit) } ?: NONE
        LiveActivitySlotOption.CARB_RATIO -> s.carbRatio?.takeIf { it > 0 }?.let { fixed(it, 0) + "g" } ?: NONE
        LiveActivitySlotOption.SAGE -> age(s.sageInsertTime)
        LiveActivitySlotOption.CAGE -> age(s.cageInsertTime)
        LiveActivitySlotOption.IAGE -> age(s.iageInsertTime)
        LiveActivitySlotOption.CARBS_TODAY -> s.carbsToday?.let { "${it.roundToInt()}g" } ?: NONE
        LiveActivitySlotOption.OVERRIDE -> s.override ?: NONE
        LiveActivitySlotOption.PROFILE -> s.profileName ?: NONE
    }

    private fun target(s: GlucoseSnapshot): String {
        val low = s.targetLowMgdl?.takeIf { it > 0 } ?: return NONE
        val lowStr = glucoseValue(low, s.unit)
        val high = s.targetHighMgdl
        if (high != null && high > 0 && abs(high - low) > 0.5) return "$lowStr-${glucoseValue(high, s.unit)}"
        return lowStr
    }

    private fun age(insertTime: Double): String {
        if (insertTime <= 0) return NONE
        return positionalDaysHours(nowSeconds() - insertTime)
    }

    fun tempTargetValue(s: GlucoseSnapshot): String? = s.tempTargetMgdl?.takeIf { it > 0 }?.let { glucoseValue(it, s.unit) }

    fun updated(s: GlucoseSnapshot): String = time(s.updatedAt)

    fun time(seconds: Double): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((seconds * 1000).toLong()))
}

/** Threshold-driven colors, as ARGB ints. */
object LAColors {
    private const val RED = 0xFFFF3B30.toInt()
    private const val ORANGE = 0xFFFF9500.toInt()
    private const val GREEN = 0xFF34C759.toInt()

    // ANDROID-DEVIATION: the range tint is blended over a fixed dark base to an opaque color; a notification has no
    // translucent lock-screen material for the tint to sit on.
    private const val BASE = 0xFF1C1C1E.toInt()

    fun backgroundTint(s: GlucoseSnapshot): Int {
        val mgdl = s.glucose
        val (low, high) = UnitSettingsStore.effectiveThresholds()
        return when {
            mgdl <= low -> {
                val raw = 0.48 + (0.85 - 0.48) * ((low - mgdl) / maxOf(low - GlucoseThresholds.VERY_LOW, 1.0))
                blend(RED, raw.coerceIn(0.48, 0.85))
            }
            mgdl >= high -> {
                val raw = 0.44 + (0.85 - 0.44) * ((mgdl - high) / maxOf(324.0 - high, 1.0))
                blend(ORANGE, raw.coerceIn(0.44, 0.85))
            }
            else -> blend(GREEN, 0.36)
        }
    }

    fun keyline(s: GlucoseSnapshot): Int {
        val (low, high) = UnitSettingsStore.effectiveThresholds()
        return when {
            s.glucose <= low -> RED
            s.glucose >= high -> ORANGE
            else -> GREEN
        }
    }

    const val NOT_LOOPING = 0xD9FF3B30.toInt()

    private fun blend(color: Int, opacity: Double): Int {
        fun channel(shift: Int): Int {
            val c = (color shr shift) and 0xFF
            val b = (BASE shr shift) and 0xFF
            return (b + (c - b) * opacity).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
