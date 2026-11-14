package com.loopandlearn.loopfollow.ui.alarms

import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.alarm.AlarmType
import com.loopandlearn.loopfollow.alarm.LowBGCondition

/** One section of an alarm editor between the General and Active sections. */
sealed class EditorSection {
    abstract val header: String?
    abstract val footer: String?

    class Stepper(
        override val header: String?,
        override val footer: String?,
        val title: String,
        val range: ClosedFloatingPointRange<Double>,
        val step: Double,
        val unit: String?,
        val get: (Alarm) -> Double?,
        val set: (Alarm, Double) -> Alarm,
    ) : EditorSection()

    class BG(
        override val header: String?,
        override val footer: String?,
        val title: String,
        val range: ClosedFloatingPointRange<Double>,
        val get: (Alarm) -> Double?,
        val set: (Alarm, Double) -> Alarm,
    ) : EditorSection()

    class BGLimit(
        override val header: String?,
        override val footer: String?,
        val toggleText: String,
        val pickerTitle: String,
        val range: ClosedFloatingPointRange<Double>,
        val defaultOnValue: Double?,
        val get: (Alarm) -> Double?,
        val set: (Alarm, Double?) -> Alarm,
    ) : EditorSection()

    class Toggle(
        override val header: String?,
        override val footer: String?,
        val title: String,
        val get: (Alarm) -> Boolean,
        val set: (Alarm, Boolean) -> Alarm,
    ) : EditorSection()
}

class EditorSpec(
    val info: String,
    val sections: List<EditorSection>,
    val hideRepeat: Boolean = false,
    val showSnooze: Boolean = true,
)

private fun intStepper(
    header: String?, footer: String?, title: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String?,
    get: (Alarm) -> Int?, set: (Alarm, Int) -> Alarm,
) = EditorSection.Stepper(header, footer, title, range, step, unit, { a -> get(a)?.toDouble() }, { a, v -> set(a, v.toInt()) })

private fun threshold(header: String?, footer: String?, title: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String?) =
    EditorSection.Stepper(header, footer, title, range, step, unit, { it.threshold }, { a, v -> a.copy(threshold = v) })

private fun delta(header: String?, footer: String?, title: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String?) =
    EditorSection.Stepper(header, footer, title, range, step, unit, { it.delta }, { a, v -> a.copy(delta = v) })

private fun window(header: String?, footer: String?, title: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String?) =
    intStepper(header, footer, title, range, step, unit, { it.monitoringWindow }, { a, v -> a.copy(monitoringWindow = v) })

private fun predictive(header: String?, footer: String?, title: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String?) =
    intStepper(header, footer, title, range, step, unit, { it.predictiveMinutes }, { a, v -> a.copy(predictiveMinutes = v) })

private fun belowLimit(header: String, footer: String, toggle: String, picker: String, range: ClosedFloatingPointRange<Double>, default: Double?) =
    EditorSection.BGLimit(header, footer, toggle, picker, range, default, { it.belowBG }, { a, v -> a.copy(belowBG = v) })

private fun aboveLimit(header: String, footer: String, toggle: String, picker: String, range: ClosedFloatingPointRange<Double>, default: Double?) =
    EditorSection.BGLimit(header, footer, toggle, picker, range, default, { it.aboveBG }, { a, v -> a.copy(aboveBG = v) })

/** The editor for each alarm type. */
fun editorSpec(type: AlarmType, alarm: Alarm): EditorSpec {
    val unit = type.snoozeTimeUnit.label
    return when (type) {
        AlarmType.BATTERY_DROP -> EditorSpec(
            "This warns you if your phone’s battery drops quickly, based on the percentage and time you set.",
            listOf(
                delta("Phone Battery Drop", "This alerts you if the phone battery drops by this much or more.", "Drop Amount", 5.0..100.0, 5.0, "%"),
                window("Over This Time", "How far back to look for that drop.", "Time Window", 5.0..30.0, 5.0, "min"),
            ),
        )
        AlarmType.BUILD_EXPIRE -> EditorSpec(
            "Sends a reminder before the looping-app build you’re following reaches its TestFlight or Xcode expiry date. Works with Trio 0.4 and later.",
            listOf(threshold("Notice Period", "Choose how many days of notice you’d like before the build becomes unusable.", "Days of notice", 1.0..14.0, 1.0, unit)),
        )
        AlarmType.COB -> EditorSpec(
            "Alerts when Carbs-on-Board is at or above the amount you set below.",
            listOf(threshold("Carbs-on-Board Limit", "Alert when Carbs-on-Board is at or above this number.", "At or Above", 1.0..200.0, 1.0, "g")),
        )
        AlarmType.DB_SIZE -> EditorSpec(
            "This warns you when the Nightscout database has filled the percentage you choose. Nightscout measures the used space against its own DBSIZE_MAX setting, which is 496 MiB unless the site owner changed it, so the percentage is only meaningful when that setting matches the real hosting limit.",
            listOf(threshold("Database Size", "This alerts you when the database reaches or exceeds this percentage of the limit configured on the Nightscout site.", "At or Above", 0.0..100.0, 5.0, "%")),
        )
        AlarmType.FAST_DROP -> EditorSpec(
            "Alerts when glucose readings drop rapidly. For example, three straight readings each falling by at least the amount you set. Optionally limit alerts to only fire below a certain BG level.",
            listOf(
                EditorSection.BG("Rate of Fall", "This is how much the glucose must drop to be considered a fast drop.", "Falls by", 3.0..54.0, { it.delta }, { a, v -> a.copy(delta = v) }),
                window("Consecutive Drops", "Number of drops—each meeting the rate above—required before an alert fires.", "Number of Drops", 1.0..3.0, 1.0, null),
                belowLimit("BG Limit", "When enabled, this alert only fires if the glucose is below the limit you set.", "Use BG Limit", "Dropping below", 40.0..300.0, 120.0),
            ),
        )
        AlarmType.FAST_RISE -> EditorSpec(
            "Alerts when glucose readings rise rapidly. For example, three straight readings each climbing by at least the amount you set.  Optionally limit alerts to only fire above a certain BG.",
            listOf(
                EditorSection.BG("Rate of Rise", "This is how much the glucose must rise to be considered a fast rise.", "Rises by", 3.0..54.0, { it.delta }, { a, v -> a.copy(delta = v) }),
                window("Consecutive Rises", "Number of rises—each meeting the rate above—required before an alert fires.", "Rises in a row", 1.0..3.0, 1.0, null),
                aboveLimit("BG Limit", "When enabled, this alert only fires if the glucose is above the limit you set.", "Use BG Limit", "Rising above", 40.0..300.0, 200.0),
            ),
        )
        AlarmType.FUTURE_CARBS -> EditorSpec(
            "Alerts when a future-dated carb entry's scheduled time arrives — a reminder to start eating. Use the max lookahead to ignore fat/protein entries that are typically scheduled further ahead.",
            listOf(
                threshold("Max Lookahead", "Only track carb entries scheduled up to this many minutes in the future. Entries beyond this window are ignored.", "Lookahead", 5.0..120.0, 5.0, "min"),
                delta("Minimum Carbs", "Ignore carb entries below this amount.", "At or Above", 0.0..50.0, 1.0, "g"),
            ),
        )
        AlarmType.HIGH -> EditorSpec(
            "Alerts when glucose stays above the limit you set below. Use Persistent if you want to ignore brief spikes.",
            listOf(
                EditorSection.BG("High Glucose Limit", "The alert becomes eligible once any reading is at or above this value.", "BG", 120.0..350.0, { it.aboveBG }, { a, v -> a.copy(aboveBG = v) }),
                intStepper("Persistent High", "How long glucose must remain above the threshold before the alarm actually fires.  Set to 0 for an immediate alert.", "Persistent for", 0.0..120.0, 5.0, unit, { it.persistentMinutes }, { a, v -> a.copy(persistentMinutes = v) }),
                EditorSection.Toggle("FALLING BG", "Stay silent while BG is falling. The alert only sounds when the latest reading is flat or still rising.", "Skip if BG is falling", { it.suppressIfFalling }, { a, v -> a.copy(suppressIfFalling = v) }),
            ),
        )
        AlarmType.IOB -> EditorSpec(
            "Alerts when insulin-on-board is high, or when several boluses in quick succession exceed the limits you set.",
            listOf(
                delta("Boluses Size Limit", "This counts only boluses larger than this size.", "Above", 0.1..20.0, 0.1, "Units"),
                window("Bolus Count", "Number of qualifying boluses needed to trigger.", "Count", 1.0..10.0, 1.0, "Boluses"),
                predictive("Time Window", "How far back to look for those boluses.", "Time", 5.0..120.0, 5.0, "min"),
                threshold("Insulin On Board", "Alert if current IOB or total boluses reach this.", "IOB Above", 1.0..20.0, 0.5, "Units"),
            ),
        )
        AlarmType.LOW -> EditorSpec(
            "This warns you if the glucose is too low now or might be soon, based on the forecast.",
            listOf(
                EditorSection.BG("Low Limit", "Alert when any reading or forecast is at or below this value.", "BG", 40.0..150.0, { it.belowBG }, { a, v -> a.copy(belowBG = v) }),
                intStepper("PERSISTENCE", "Glucose must stay below the threshold for this many minutes before the alert sounds. Set 0 to alert immediately.", "Persistent", 0.0..120.0, 5.0, unit, { it.persistentMinutes }, { a, v -> a.copy(persistentMinutes = v) }),
                predictive("PREDICTION", "Look ahead this many minutes in the forecast; if any future value is at or below the threshold, you’ll be warned early. Set 0 to disable.", "Predictive", 0.0..LowBGCondition.MAX_PREDICTIVE_MINUTES.toDouble(), 5.0, unit),
                EditorSection.Toggle("RISING BG", "Stay silent while BG is rising. The alert only sounds when the latest reading is flat or still falling.", "Skip if BG is rising", { it.suppressIfRising }, { a, v -> a.copy(suppressIfRising = v) }),
            ),
        )
        AlarmType.MISSED_BOLUS -> EditorSpec(
            "Alerts when carbs are logged but no bolus is delivered within the delay below.  Allows small-carb / treatment exclusions and pre-bolus detection.",
            listOf(
                window("Delay", "Minutes to wait after the carb entry before checking for a bolus.", "Delay", 5.0..60.0, 5.0, "min"),
                predictive("Pre-bolus", "Count boluses given up to this many minutes before the carb entry as valid.", "Pre-Bolus Time", 0.0..45.0, 5.0, "min"),
                delta("Ignore small boluses", "Boluses at or below this size are ignored.", "At or Below", 0.05..2.0, 0.05, "Units"),
                threshold("Ignore small carbs", "Carb entries at or below this amount will not trigger the alarm.", "At or Below", 0.0..15.0, 1.0, "Grams"),
                aboveLimit("Ignore low BG", "Only alert if the current BG is above this value.", "Use BG Limit", "Above", 40.0..140.0, 70.0),
            ),
        )
        AlarmType.MISSED_READING -> EditorSpec(
            "This warns you if the glucose monitor stops sending readings for too long..",
            listOf(threshold("Reading Delay", "Choose how long the app should wait before alerting.", "No reading for", 11.0..121.0, 5.0, unit)),
        )
        AlarmType.NOT_LOOPING -> EditorSpec(
            "Alerts when no successful loop has occurred for the time you set below.",
            listOf(
                threshold("No Loop for…", "Number of minutes since the last successful loop. When this time has elapsed, the alarm becomes eligible.", "Elapsed time", 16.0..61.0, 5.0, unit),
                belowLimit("Low Limit", "Alert only if BG is equal to or below this value.", "Enable low limit", "Below", 40.0..300.0, 100.0),
                aboveLimit("High Limit", "Alert only if BG is equal to or above this value.", "Enable high limit", "Above", 40.0..300.0, 160.0),
            ),
        )
        AlarmType.OVERRIDE_END -> EditorSpec(
            "Alerts when an override ends, with an optional early warning before the scheduled end.",
            listOf(predictive("Early Warning", "Also alert this many minutes before the override is scheduled to end. Set to 0 to alert only when it ends.", "Warn before end", 0.0..30.0, 5.0, unit)),
            hideRepeat = true,
        )
        AlarmType.OVERRIDE_START -> EditorSpec("Alerts when an override begins.", emptyList(), hideRepeat = true)
        AlarmType.BATTERY -> EditorSpec(
            "This warns you when the phone's battery gets low, based on the percentage you choose.",
            listOf(
                threshold("Phone Battery Level", "This alerts you when the phone battery drops to or below this level.", "At or Below", 0.0..100.0, 5.0, "%"),
                EditorSection.Toggle("CHARGING", "Stay silent while the phone is charging. Requires the uploader to report charging status; if it doesn't, the alert still sounds.", "Skip while charging", { it.suppressIfCharging }, { a, v -> a.copy(suppressIfCharging = v) }),
            ),
        )
        AlarmType.PUMP_BATTERY -> EditorSpec(
            "This warns you when the pump's battery gets low, based on the percentage you choose.",
            listOf(threshold("Pump Battery Level", "This alerts you when the pump battery drops to or below this level.", "At or Below", 0.0..100.0, 5.0, "%")),
        )
        AlarmType.PUMP_CHANGE -> EditorSpec(
            "Alerts when the pump / cannula is within the time window you choose below (relative to the 3-day change limit).",
            listOf(threshold("Advance Notice", "How many hours before the 3-day limit you’d like a reminder.", "Warning Time", 1.0..24.0, 1.0, "Hours")),
        )
        AlarmType.PUMP -> EditorSpec(
            "This warns you when the insulin pump is running low on insulin.",
            listOf(threshold("Trigger Level", "An alert fires once the reservoir is at this value or lower.", "Reservoir Below", 1.0..50.0, 1.0, "Units")),
        )
        AlarmType.REC_BOLUS -> EditorSpec(
            "Alerts when the recommended bolus equals or exceeds the threshold you set below.",
            listOf(threshold("Threshold", "Alert when recommended bolus is at or above this value.", "At or Above", 0.1..50.0, 0.1, "Units")),
        )
        AlarmType.SENSOR_CHANGE -> {
            val days = alarm.sensorLifetimeDays ?: 10
            EditorSpec(
                "Warn me before the sensor’s $days-day change-over.",
                listOf(
                    intStepper("Sensor Lifetime", "Number of days your CGM sensor lasts (e.g. 10 for Dexcom G6, 15 for G7 15-day).", "Lifetime", 7.0..15.0, 1.0, "days", { it.sensorLifetimeDays ?: 10 }, { a, v -> a.copy(sensorLifetimeDays = v) }),
                    threshold("Early Reminder", "Number of hours before the $days-day mark that the alert will fire.", "Reminder Time", 1.0..24.0, 1.0, "hours"),
                ),
            )
        }
        AlarmType.TEMPORARY -> EditorSpec(
            "This alert fires once when glucose crosses either of the limits you set below, and then disables itself.",
            listOf(
                belowLimit("Low Limit", "Alert if BG is equal to or below this value.", "Enable low limit", "Below", 40.0..300.0, null),
                aboveLimit("High Limit", "Alert if BG is equal to or above this value.", "Enable high limit", "Above", 40.0..300.0, null),
            ),
            showSnooze = false,
        )
        AlarmType.TEMP_TARGET_END -> EditorSpec(
            "Alerts when a temp target ends, with an optional early warning before the scheduled end.",
            listOf(predictive("Early Warning", "Also alert this many minutes before the temp target is scheduled to end. Set to 0 to alert only when it ends.", "Warn before end", 0.0..30.0, 5.0, unit)),
            hideRepeat = true,
        )
        AlarmType.TEMP_TARGET_START -> EditorSpec("Alerts when a temp target starts.", emptyList(), hideRepeat = true)
    }
}
