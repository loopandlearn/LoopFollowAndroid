package com.loopandlearn.loopfollow.helpers

import com.loopandlearn.loopfollow.storage.Storage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Globals {
    // Glucose display range (mg/dL). Values at or below the min are shown as "LOW";
    // values at or above the max are shown as "HIGH". Also clamps graph values.
    const val MIN_DISPLAY_GLUCOSE = 39
    const val MAX_DISPLAY_GLUCOSE = 400

    // Number of apps that may upload BG to the same account. The Nightscout
    // entry-count request is multiplied by this to avoid truncating history.
    const val MAX_EXPECTED_UPLOADERS = 4
}

fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

object DateTimeUtils {
    private val availableTimeZoneIDs by lazy { TimeZone.getAvailableIDs().toSet() }

    fun displayTimeZone(): TimeZone {
        if (Storage.graphTimeZoneEnabled.value) {
            val id = Storage.graphTimeZoneIdentifier.value
            if (id in availableTimeZoneIDs) return TimeZone.getTimeZone(id)
        }
        return TimeZone.getDefault()
    }

    fun midnightToday(): Double {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000.0
    }

    fun nHoursAgo(n: Int): Double = nowSeconds() - n * 3600.0

    /** UTC date-time string, "yyyy-MM-dd'T'HH:mm:ss", offset from now. */
    fun dateTimeString(addingMinutes: Int = 0, addingHours: Int = 0, addingDays: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, addingMinutes)
        cal.add(Calendar.HOUR_OF_DAY, addingHours)
        cal.add(Calendar.DAY_OF_YEAR, addingDays)
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(cal.time)
    }

    fun isToday(seconds: Double): Boolean {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = (seconds * 1000).toLong() }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    fun shortTime(seconds: Double): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date((seconds * 1000).toLong()))

    fun formattedDate(seconds: Double?): String {
        seconds ?: return "Unknown"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            .format(Date((seconds * 1000).toLong()))
    }
}

object CycleHelper {
    /** Seconds into the cycle relative to local midnight. */
    fun cycleOffset(timestamp: Double, interval: Double): Double {
        val cal = Calendar.getInstance().apply { timeInMillis = (timestamp * 1000).toLong() }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val sinceMidnight = timestamp - cal.timeInMillis / 1000.0
        return sinceMidnight % interval
    }
}

/** A duration in days and hours, e.g. "2d 5h". */
fun positionalDaysHours(seconds: Double): String {
    val totalHours = (seconds / 3600).toInt()
    val days = totalHours / 24
    val hours = totalHours % 24
    return "${days}d ${hours}h"
}

/**
 * iOS DateComponentsFormatter, positional, dropLeading, for minutes(+seconds).
 */
fun positionalMinutes(seconds: Double, showSeconds: Boolean): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (showSeconds) {
        val m = total / 60
        val s = total % 60
        if (m == 0L) "$s" else String.format(Locale.getDefault(), "%d:%02d", m, s)
    } else {
        "${(seconds / 60).toLong()}"
    }
}

/**
 * A CGM state without reliable glucose, as uploaded by Loop or Trio in a
 * Nightscout note of the form "CGM: <state>".
 */
data class CGMSensorState(val name: String, val date: Double) {
    val displayName: String get() = displayName(name)

    /** The state and its time, e.g. "Temporary sensor issue at 22:33". */
    val summary: String get() = "$displayName at ${DateTimeUtils.shortTime(date)}"

    companion object {
        fun fromNote(note: String, date: Double): CGMSensorState? {
            val prefix = "CGM: "
            if (!note.startsWith(prefix)) return null
            val name = note.removePrefix(prefix)
            if (name.isEmpty() || name.any { it.isWhitespace() }) return null
            return CGMSensorState(name, date)
        }

        /** The newest state reported after the newest reading. */
        fun active(states: List<CGMSensorState>, latestBGDate: Double?): CGMSensorState? {
            val newest = states.maxByOrNull { it.date } ?: return null
            if (latestBGDate != null && newest.date <= latestBGDate) return null
            return newest
        }

        fun displayName(name: String): String {
            if (name.startsWith(".unknown(") && name.endsWith(")")) {
                val code = name.removePrefix(".unknown(").removeSuffix(")")
                return "Unknown state ($code)"
            }
            if (name == "questionMarks") return "Question marks (???)"

            val identifier = name.replace("Dueto", "DueTo")
            val words = mutableListOf<String>()
            var current = StringBuilder()
            var previous: Char? = null
            for (c in identifier) {
                if (previous != null) {
                    val startsWord = (c.isUpperCase() && (previous.isLowerCase() || previous.isDigit())) ||
                        (c.isDigit() && previous.isLetter())
                    if (startsWord) {
                        words.add(current.toString())
                        current = StringBuilder()
                    }
                }
                current.append(c)
                previous = c
            }
            words.add(current.toString())

            val normalized = words.map { word ->
                if (word.drop(1).any { it.isUpperCase() }) word else word.lowercase()
            }
            val first = normalized.firstOrNull()
            if (first.isNullOrEmpty()) return name
            return (listOf(first.take(1).uppercase() + first.drop(1)) + normalized.drop(1)).joinToString(" ")
        }
    }
}
