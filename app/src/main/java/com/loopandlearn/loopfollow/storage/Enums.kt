package com.loopandlearn.loopfollow.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AppearanceMode(val displayName: String) {
    @SerialName("system") SYSTEM("System"),
    @SerialName("light") LIGHT("Light"),
    @SerialName("dark") DARK("Dark"),
}

@Serializable
enum class PredictionDisplayType(val displayName: String) {
    @SerialName("cone") CONE("Cone"),
    @SerialName("lines") LINES("Lines"),
}

/** Where the device status history curves (IOB, COB, sensitivity ratio) are drawn. */
@Serializable
enum class HistoryCurvePlacement(val displayName: String) {
    @SerialName("separate") SEPARATE("Separate Graphs"),
    @SerialName("mainGraph") MAIN_GRAPH("In Main Graph"),
}

@Serializable
enum class RemoteType(val displayName: String) {
    @SerialName("None") NONE("None"),
    @SerialName("Trio Remote Control") TRC("Trio Remote Control"),
    @SerialName("Loop APNS") LOOP_APNS("Loop APNS"),
}

/**
 * How the app keeps running in the background. On Android a foreground service
 * keeps the data pipeline alive; the type only decides whether that runs.
 */
@Serializable
enum class BackgroundRefreshType(val displayName: String) {
    @SerialName("None") NONE("None"),
    @SerialName("Foreground Service") FOREGROUND_SERVICE("Foreground Service"),
}

@Serializable
enum class TabPosition(val displayName: String) {
    @SerialName("position1") POSITION1("Tab 1"),
    @SerialName("position2") POSITION2("Tab 2"),
    @SerialName("position3") POSITION3("Tab 3"),
    @SerialName("position4") POSITION4("Tab 4"),
    @SerialName("menu") MENU("Menu"),
    ;

    companion object {
        val customizablePositions = listOf(POSITION1, POSITION2, POSITION3, POSITION4)
    }
}

@Serializable
enum class TabItem(val displayName: String) {
    @SerialName("home") HOME("Home"),
    @SerialName("alarms") ALARMS("Alarms"),
    @SerialName("remote") REMOTE("Remote"),
    @SerialName("nightscout") NIGHTSCOUT("Nightscout"),
    @SerialName("snoozer") SNOOZER("Snoozer"),
    @SerialName("treatments") TREATMENTS("Treatments"),
    @SerialName("stats") STATS("Statistics"),
    ;

    companion object {
        /** Canonical feature order used by menus and customization screens. */
        val featureOrder = listOf(HOME, ALARMS, NIGHTSCOUT, REMOTE, SNOOZER, STATS, TREATMENTS)
    }
}

@Serializable
enum class GlucoseDisplayUnit(val raw: String, val fractionDigits: Int) {
    @SerialName("mg/dL") MGDL("mg/dL", 0),
    @SerialName("mmol/L") MMOLL("mmol/L", 1),
    ;

    companion object {
        fun fromRaw(raw: String) = entries.firstOrNull { it.raw == raw } ?: MGDL
    }
}

/**
 * The selectable range modes. The raw value is persisted, so don't change existing ones.
 */
enum class TimeInRangeDisplayMode(val raw: String, val title: String) {
    TIR("TIR", "Time in Range"),
    TITR("TITR", "Time in Tight Range"),
    TING("TING", "Extended Tight Range"),
    CUSTOM("Custom", "Custom Range"),
    ;

    /** Low/high cut-offs in mg/dL, or null when the user's own low/high lines apply. */
    val fixedThresholds: Pair<Double, Double>?
        get() = when (this) {
            TIR -> GlucoseThresholds.TIR_LOW to GlucoseThresholds.TIR_HIGH
            TITR -> GlucoseThresholds.TITR_LOW to GlucoseThresholds.TITR_HIGH
            TING -> GlucoseThresholds.TING_LOW to GlucoseThresholds.TING_HIGH
            CUSTOM -> null
        }

    val next: TimeInRangeDisplayMode
        get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromRaw(raw: String) = entries.firstOrNull { it.raw == raw } ?: TIR
    }
}

object GlucoseThresholds {
    const val VERY_LOW = 54.0
    const val VERY_HIGH = 250.0
    /** Time in Range, also used by GRI regardless of the selected range mode. */
    const val TIR_LOW = 70.0
    const val TIR_HIGH = 180.0
    /** Time in Tight Range. */
    const val TITR_LOW = 70.0
    const val TITR_HIGH = 140.0
    /** Extended Tight Range (TING, Time in Normoglycemia). */
    const val TING_LOW = 63.0
    const val TING_HIGH = 140.0
}

object GlucoseConversion {
    const val MMOL_TO_MGDL = 18.01559
    const val MGDL_TO_MMOL = 1.0 / MMOL_TO_MGDL

    fun toMmol(mgdl: Double) = mgdl * MGDL_TO_MMOL
}
