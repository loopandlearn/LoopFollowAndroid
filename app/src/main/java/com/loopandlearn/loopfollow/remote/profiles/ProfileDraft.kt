package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.remote.APNSDelivery
import java.util.Date
import java.util.TimeZone
import java.util.UUID

object ProfilePushSize {
    /**
     * The largest measured size the review accepts. Trio's push is encrypted with a fresh nonce and timestamp at
     * send time, which changes its size by a few bytes, so it keeps a margin; Loop's measured size is the size sent.
     */
    fun reviewLimit(remote: ProfileRemote): Int = APNSDelivery.MAX_PAYLOAD_BYTES - if (remote == ProfileRemote.TRIO) 64 else 0

    fun tooLargeMessage(size: Int) =
        "This profile is too large to send remotely ($size of ${APNSDelivery.MAX_PAYLOAD_BYTES} bytes). Use fewer time segments."

    fun scheduledSwitchesTooLargeMessage(size: Int) =
        "The scheduled switches are too large to send remotely ($size of ${APNSDelivery.MAX_PAYLOAD_BYTES} bytes). Use shorter profile names."
}

/** One editable schedule entry. Single-value schedules use [value]; the correction range uses [value] as low and [high]. */
data class EditableSegment(
    val seconds: Int,
    val value: Double,
    val high: Double = value,
    val id: String = UUID.randomUUID().toString(),
)

/** The schedules of a profile while they are edited. */
data class ProfileDraft(
    val units: String,
    val basal: List<EditableSegment>,
    val carbRatio: List<EditableSegment>,
    val sensitivity: List<EditableSegment>,
    val target: List<EditableSegment>,
) {
    val values: TherapyProfileValues
        get() {
            fun items(segments: List<EditableSegment>) = segments.map { ProfileScheduleItem(it.seconds, it.value) }
            return TherapyProfileValues(units, items(basal), items(carbRatio), items(sensitivity), target.map { ProfileRangeItem(it.seconds, it.value, it.high) })
        }

    operator fun get(kind: ProfileScheduleKind): List<EditableSegment> = when (kind) {
        ProfileScheduleKind.BASAL -> basal
        ProfileScheduleKind.CARB_RATIO -> carbRatio
        ProfileScheduleKind.SENSITIVITY -> sensitivity
        ProfileScheduleKind.CORRECTION_RANGE -> target
    }

    fun with(kind: ProfileScheduleKind, segments: List<EditableSegment>): ProfileDraft = when (kind) {
        ProfileScheduleKind.BASAL -> copy(basal = segments)
        ProfileScheduleKind.CARB_RATIO -> copy(carbRatio = segments)
        ProfileScheduleKind.SENSITIVITY -> copy(sensitivity = segments)
        ProfileScheduleKind.CORRECTION_RANGE -> copy(target = segments)
    }

    companion object {
        /** With [singleTarget], each target's high value is its low value. */
        fun of(values: TherapyProfileValues, singleTarget: Boolean = false) = ProfileDraft(
            values.units,
            values.basal.map { EditableSegment(it.seconds, it.value) },
            values.carbRatio.map { EditableSegment(it.seconds, it.value) },
            values.sensitivity.map { EditableSegment(it.seconds, it.value) },
            values.target.map { EditableSegment(it.seconds, it.low, if (singleTarget) it.low else it.high) },
        )
    }
}

/** Segment operations on the time grid of the published limits. */
object ProfileSegmentGrid {
    /**
     * The start time a new segment gets: one step after the last segment, or the first free slot when the
     * last segment is at the end of the day. Null when the schedule is full.
     */
    fun newSegmentStart(segments: List<EditableSegment>, segmentSeconds: Int, maxSegments: Int): Int? {
        if (segments.size >= maxSegments || segmentSeconds <= 0) return null
        val starts = segments.map { it.seconds }.sorted()
        val last = starts.lastOrNull()
        if (last != null && last + segmentSeconds < 86400) return (last / segmentSeconds + 1) * segmentSeconds
        var slot = 0
        while (slot < 86400) {
            if (slot !in starts) return slot
            slot += segmentSeconds
        }
        return null
    }

    /** Adds a segment that starts with the value in effect at its start time. */
    fun addSegment(segments: List<EditableSegment>, segmentSeconds: Int, maxSegments: Int): List<EditableSegment> {
        val start = newSegmentStart(segments, segmentSeconds, maxSegments) ?: return segments
        val current = segments.lastOrNull { it.seconds <= start } ?: segments.firstOrNull()
        return (segments + EditableSegment(start, current?.value ?: 0.0, current?.high ?: current?.value ?: 0.0)).sortedBy { it.seconds }
    }

    /** Start times a segment can move to: between its neighbours on the grid. The first segment stays at midnight. */
    fun allowedStarts(index: Int, segments: List<EditableSegment>, segmentSeconds: Int): List<Int> {
        if (index <= 0 || index >= segments.size || segmentSeconds <= 0) return listOf(0)
        val lower = segments[index - 1].seconds + segmentSeconds
        val upper = if (index + 1 < segments.size) segments[index + 1].seconds else 86400
        val first = (lower + segmentSeconds - 1) / segmentSeconds * segmentSeconds
        return (first until upper step segmentSeconds).toList()
    }
}

/** A schedule in which one set of profile values gives more insulin than another, and the times of day it does. */
data class ProfileInsulinIncrease(
    val kind: ProfileScheduleKind,
    /** Start and end of each period, in seconds from midnight, end exclusive. */
    val periods: List<IntRange>,
) {
    /** For example "Basal Rates higher 06:00–09:00". */
    val text: String
        get() {
            val times = periods.map { period ->
                if (period.first == 0 && period.last == 86400) {
                    "all day"
                } else {
                    val end = if (period.last == 86400) "midnight" else ProfileValueFormat.timeText(period.last)
                    "${ProfileValueFormat.timeText(period.first)}–$end"
                }
            }
            return "${kind.title} ${kind.moreInsulinWord} ${times.joinToString(", ")}"
        }

    /** True when [seconds] falls in one of the periods. */
    fun covers(seconds: Int) = periods.any { it.first <= seconds && seconds < it.last }
}

/**
 * Compares two sets of profile values by the insulin they lead to. Higher basal rates, lower carb ratios,
 * lower insulin sensitivities and a lower correction range all give more insulin.
 */
object ProfileInsulinComparison {
    private const val TOLERANCE = 1e-6

    fun increases(old: TherapyProfileValues, new: TherapyProfileValues): List<ProfileInsulinIncrease> =
        ProfileScheduleKind.entries.mapNotNull { kind ->
            val periods = increasePeriods(kind, old, new)
            if (periods.isEmpty()) null else ProfileInsulinIncrease(kind, periods)
        }

    /** Scheduled basal over a day before and after, when the day total rises. */
    fun basalTotalIncrease(old: TherapyProfileValues, new: TherapyProfileValues): Pair<Double, Double>? {
        val before = TherapyProfile.basalTotal(old.basal)
        val after = TherapyProfile.basalTotal(new.basal)
        return if (after > before + TOLERANCE) before to after else null
    }

    private class Point(val seconds: Int, val value: Double, val high: Double)

    private fun increasePeriods(kind: ProfileScheduleKind, old: TherapyProfileValues, new: TherapyProfileValues): List<IntRange> {
        val before = points(kind, old)
        val after = points(kind, new)
        if (before.isEmpty() || after.isEmpty()) return emptyList()
        val times = (before.map { it.seconds } + after.map { it.seconds }).toSortedSet().toList()
        fun value(points: List<Point>, seconds: Int) = points.lastOrNull { it.seconds <= seconds } ?: points[0]
        val periods = mutableListOf<IntRange>()
        for ((index, start) in times.withIndex()) {
            val end = if (index + 1 < times.size) times[index + 1] else 86400
            if (!givesMoreInsulin(kind, value(before, start), value(after, start))) continue
            val last = periods.lastOrNull()
            if (last != null && last.last == start) periods[periods.size - 1] = last.first..end else periods.add(start..end)
        }
        return periods
    }

    /** The schedule with glucose values in mg/dL, so profiles in different units compare correctly. */
    private fun points(kind: ProfileScheduleKind, values: TherapyProfileValues): List<Point> {
        val factor = if (TherapyProfileValues.normalizedUnits(values.units) == TherapyProfileValues.MGDL) 1.0 else 18.01559
        return when (kind) {
            ProfileScheduleKind.BASAL -> values.basal.map { Point(it.seconds, it.value, it.value) }
            ProfileScheduleKind.CARB_RATIO -> values.carbRatio.map { Point(it.seconds, it.value, it.value) }
            ProfileScheduleKind.SENSITIVITY -> values.sensitivity.map { Point(it.seconds, it.value * factor, it.value * factor) }
            ProfileScheduleKind.CORRECTION_RANGE -> values.target.map { Point(it.seconds, it.low * factor, it.high * factor) }
        }
    }

    private fun givesMoreInsulin(kind: ProfileScheduleKind, old: Point, new: Point): Boolean {
        fun lower(a: Double, b: Double) = a < b - TOLERANCE * maxOf(1.0, kotlin.math.abs(b))
        return when (kind) {
            ProfileScheduleKind.BASAL -> lower(old.value, new.value)
            ProfileScheduleKind.CARB_RATIO, ProfileScheduleKind.SENSITIVITY -> lower(new.value, old.value)
            ProfileScheduleKind.CORRECTION_RANGE -> lower(new.value, old.value) || lower(new.high, old.high)
        }
    }
}

/** One row of a schedule comparison: the value in effect from [seconds] before and after the change. */
data class ProfileDiffRow(val seconds: Int, val old: String, val new: String) {
    val changed: Boolean get() = old != new
}

object ProfileDiff {
    /** The value in effect at each time either schedule changes value, before and after. */
    fun rows(old: List<EditableSegment>, new: List<EditableSegment>, text: (EditableSegment) -> String): List<ProfileDiffRow> {
        val times = (old.map { it.seconds } + new.map { it.seconds }).toSortedSet()
        fun effective(segments: List<EditableSegment>, seconds: Int) = segments.lastOrNull { it.seconds <= seconds }?.let(text) ?: "—"
        return times.map { ProfileDiffRow(it, effective(old, it), effective(new, it)) }
    }
}

/**
 * Formats profile values in the profile's own units. Glucose values in mmol/L have one decimal; other values
 * show as many decimals as they have (up to three), so they are never shown rounded.
 */
object ProfileValueFormat {
    fun text(value: Double, decimals: Int): String = Localizer.formatToLocalizedString(value, maxOf(decimals, 3), decimals)

    fun text(value: Double, kind: ProfileScheduleKind, limits: ProfileEditLimits?, glucoseUnits: String): String {
        if (kind.isGlucose && TherapyProfileValues.normalizedUnits(glucoseUnits) == TherapyProfileValues.MMOL) {
            return Localizer.formatToLocalizedString(value, 1, 1)
        }
        return text(value, limits?.let { kind.decimals(it) } ?: defaultDecimals(kind))
    }

    fun defaultDecimals(kind: ProfileScheduleKind) = if (kind == ProfileScheduleKind.BASAL) 2 else 0

    fun segmentText(segment: EditableSegment, kind: ProfileScheduleKind, limits: ProfileEditLimits?, glucoseUnits: String, singleTarget: Boolean = false): String {
        if (kind.isRange && !singleTarget) {
            return "${text(segment.value, kind, limits, glucoseUnits)} – ${text(segment.high, kind, limits, glucoseUnits)}"
        }
        return text(segment.value, kind, limits, glucoseUnits)
    }

    /** Schedule start times are offsets from midnight in the profile's time zone. */
    fun timeText(seconds: Int): String {
        val formatter = android.text.format.DateFormat.getTimeFormat(LoopFollowApplication.instance)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(seconds * 1000L))
    }
}
