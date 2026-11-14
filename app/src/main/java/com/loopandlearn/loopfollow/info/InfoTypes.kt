package com.loopandlearn.loopfollow.info

import kotlinx.serialization.Serializable

@Serializable
enum class InfoType(val displayName: String) {
    IOB("IOB"),
    COB("COB"),
    BASAL("Basal"),
    OVERRIDE("Override"),
    BATTERY("Battery"),
    PUMP("Pump"),
    PUMP_BATTERY("Pump Battery"),
    SAGE("SAGE"),
    CAGE("CAGE"),
    REC_BOLUS("Rec. Bolus"),
    MIN_MAX("Min/Max"),
    CARBS_TODAY("Carbs today"),
    AUTOSENS("Autosens"),
    PROFILE("Profile"),
    TARGET("Target"),
    ISF("ISF"),
    CARB_RATIO("CR"),
    UPDATED("Updated"),
    TDD("TDD"),
    IAGE("IAGE"),
    DB_SIZE("DB Size"),
    SMOOTHED_BG("Smoothed BG"),
    ;

    val defaultVisible: Boolean
        get() = when (this) {
            IOB, COB, BASAL, OVERRIDE, BATTERY, PUMP, SAGE, CAGE, REC_BOLUS, MIN_MAX, CARBS_TODAY -> true
            else -> false
        }

    /**
     * Rows that carry a single numeric value can offer color thresholds. Steps
     * mirror the equivalent alarm editors.
     */
    val colorConfig: InfoColorConfig?
        get() = when (this) {
            IOB -> InfoColorConfig(InfoColorDirection.ABOVE, "U", 0.0..20.0, 0.5, 3.0, 5.0)
            COB -> InfoColorConfig(InfoColorDirection.ABOVE, "g", 0.0..200.0, 1.0, 30.0, 60.0)
            BATTERY, PUMP_BATTERY -> InfoColorConfig(InfoColorDirection.BELOW, "%", 0.0..100.0, 5.0, 30.0, 15.0)
            PUMP -> InfoColorConfig(InfoColorDirection.BELOW, "U", 0.0..50.0, 1.0, 20.0, 10.0)
            TDD -> InfoColorConfig(InfoColorDirection.ABOVE, "U", 0.0..200.0, 1.0, 60.0, 80.0)
            REC_BOLUS -> InfoColorConfig(InfoColorDirection.ABOVE, "U", 0.0..20.0, 0.1, 1.0, 2.0)
            CARBS_TODAY -> InfoColorConfig(InfoColorDirection.ABOVE, "g", 0.0..500.0, 5.0, 150.0, 250.0)
            SAGE -> InfoColorConfig(InfoColorDirection.ABOVE, "days", 0.5..15.0, 0.5, 9.0, 9.5)
            CAGE, IAGE -> InfoColorConfig(InfoColorDirection.ABOVE, "days", 0.5..10.0, 0.5, 2.5, 3.0)
            else -> null
        }

    val isColorable: Boolean get() = colorConfig != null
}

/** Which direction is "concerning" for a metric. Fixed per InfoType. */
enum class InfoColorDirection { ABOVE, BELOW }

enum class InfoColorLevel { NORMAL, WARNING, URGENT }

data class InfoColorConfig(
    val direction: InfoColorDirection,
    val unit: String,
    val range: ClosedFloatingPointRange<Double>,
    val step: Double,
    val defaultWarning: Double,
    val defaultUrgent: Double,
) {
    /** Decimals to show, derived from the step (0.5 → 1, 5 → 0). */
    val fractionDigits: Int get() = if (step % 1.0 == 0.0) 0 else 1
}

/** Per-row, opt-in color thresholds. Purely visual — never triggers an alarm. */
@Serializable
data class InfoColoring(
    val enabled: Boolean = false,
    val warning: Double? = null,
    val urgent: Double? = null,
) {
    /** The threshold band a value falls in, or null when coloring is off. */
    fun level(value: Double, direction: InfoColorDirection): InfoColorLevel? {
        if (!enabled) return null
        when (direction) {
            InfoColorDirection.ABOVE -> {
                if (urgent != null && value >= urgent) return InfoColorLevel.URGENT
                if (warning != null && value >= warning) return InfoColorLevel.WARNING
            }
            InfoColorDirection.BELOW -> {
                if (urgent != null && value <= urgent) return InfoColorLevel.URGENT
                if (warning != null && value <= warning) return InfoColorLevel.WARNING
            }
        }
        return InfoColorLevel.NORMAL
    }
}

/** One configurable row in the Info Display. */
@Serializable
data class InfoDisplayItem(
    val type: InfoType,
    val isVisible: Boolean,
    val coloring: InfoColoring,
)

fun List<InfoDisplayItem>.item(type: InfoType): InfoDisplayItem? = firstOrNull { it.type == type }

fun List<InfoDisplayItem>.isVisible(type: InfoType): Boolean = item(type)?.isVisible ?: false

enum class InfoDataSeparator(val raw: String) {
    ARROW("→"),
    SLASH("/"),
    DASH("-"),
}

data class InfoData(
    val type: InfoType,
    val value: String = "",
    /** Raw numeric value behind [value], when the row carries a single number. */
    val numericValue: Double? = null,
)
