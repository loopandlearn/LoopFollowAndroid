package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.engine.NSProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone

/** The scheduled switches in the latest profile document of Loop or Trio. */
class ScheduledSwitchList(
    /** The entries, with Trio's `default` named Default as in the profile list. */
    val entries: List<ScheduledProfileSwitch>,
    /** Fingerprint of the list as uploaded. A replace command sends it so the phone only replaces this list. */
    val fingerprint: String,
    /**
     * False when an entry could not be read or the list does not match the fingerprint the phone uploaded, so the
     * list can not be replaced faithfully.
     */
    val isComplete: Boolean,
    /** The time zone of the therapy schedules, in which the start times apply. */
    val timeZone: TimeZone,
) {
    val pausedCount: Int get() = entries.count { it.paused }

    companion object {
        val EMPTY = ScheduledSwitchList(emptyList(), ScheduledProfileSwitch.fingerprint(emptyList()), true, TimeZone.getDefault())

        operator fun invoke(profile: NSProfile): ScheduledSwitchList {
            val value = profile.json["scheduledSwitches"]
            val array = value as? JsonArray
            val readable = value == null || value is JsonNull || array != null
            val decoded = array ?: emptyList()
            val raw = decoded.mapNotNull { ScheduledProfileSwitch.from(it) }
            val fingerprint = ScheduledProfileSwitch.fingerprint(raw)
            val uploaded = (profile.json["scheduledSwitchesFingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return ScheduledSwitchList(
                raw.map { if (TherapyProfile.isDefaultName(it.profile)) it.copy(profile = TherapyProfile.DEFAULT_NAME) else it },
                fingerprint,
                readable && raw.size == decoded.size && (uploaded == null || uploaded == fingerprint),
                profile.store[profile.defaultProfile]?.let { ScheduledSwitches.timeZone(it.timezone) } ?: TimeZone.getDefault(),
            )
        }
    }
}

/** Week order, occurrences, validation and wording for scheduled switches. */
object ScheduledSwitches {
    const val SECONDS_PER_DAY = 86400

    /** A Nightscout `timezone` value, also in the `ETC/GMT+1` spelling some uploaders use. */
    fun timeZone(identifier: String): TimeZone {
        val ids = TimeZone.getAvailableIDs().toSet()
        if (identifier in ids) return TimeZone.getTimeZone(identifier)
        val adjusted = identifier.replace("ETC/", "Etc/")
        if (adjusted in ids) return TimeZone.getTimeZone(adjusted)
        val parts = identifier.uppercase().split("GMT")
        val hours = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size > 1 && hours != null) return TimeZone.getTimeZone(String.format(Locale.US, "GMT%+03d:00", -hours))
        return TimeZone.getDefault()
    }

    /** Monday first, Sunday last. */
    fun weekPosition(weekday: Int) = (weekday + 5) % 7

    /** Weekdays (Sunday = 1) from Monday. */
    val weekdaysFromMonday = listOf(2, 3, 4, 5, 6, 7, 1)

    /** Seconds from Monday 00:00. */
    fun weekSeconds(entry: ScheduledProfileSwitch) = weekPosition(entry.weekday) * SECONDS_PER_DAY + entry.time

    /** The entries as a week from Monday. */
    fun sortedForWeek(entries: List<ScheduledProfileSwitch>): List<ScheduledProfileSwitch> =
        entries.sortedWith { a, b ->
            val c = weekSeconds(a).compareTo(weekSeconds(b))
            if (c != 0) c else TherapyProfile.standardCompare(a.profile, b.profile)
        }

    /**
     * The unpaused start that ends the span of [entry]: the next unpaused start after it in the week, counting
     * round to the following week. Null when [entry] is paused or no other unpaused start exists.
     */
    fun following(entry: ScheduledProfileSwitch, entries: List<ScheduledProfileSwitch>): ScheduledProfileSwitch? {
        if (entry.paused) return null
        val week = 7 * SECONDS_PER_DAY
        val start = weekSeconds(entry)
        fun distance(other: ScheduledProfileSwitch): Int {
            val d = (weekSeconds(other) - start + week) % week
            return if (d == 0) week else d
        }
        return entries.filter { !it.paused && it.id != entry.id }.minByOrNull(::distance)
    }

    private fun dayOfWeek(weekday: Int): DayOfWeek = DayOfWeek.of((weekday + 5) % 7 + 1)

    /** The next start of [entry] after [seconds] (epoch), in [timeZone]. */
    fun nextOccurrence(entry: ScheduledProfileSwitch, seconds: Double, timeZone: TimeZone): Double? {
        if (entry.weekday !in 1..7) return null
        val after = ZonedDateTime.ofInstant(Instant.ofEpochMilli((seconds * 1000).toLong()), timeZone.toZoneId())
        var candidate = after.toLocalDate()
        while (candidate.dayOfWeek != dayOfWeek(entry.weekday)) candidate = candidate.plusDays(1)
        var date = candidate.atStartOfDay(timeZone.toZoneId()).withHour(entry.time / 3600).withMinute(entry.time % 3600 / 60)
        if (!date.isAfter(after)) date = date.plusWeeks(1)
        return date.toInstant().toEpochMilli() / 1000.0
    }

    /** The next unpaused start after [seconds]. */
    fun next(entries: List<ScheduledProfileSwitch>, seconds: Double, timeZone: TimeZone): Pair<ScheduledProfileSwitch, Double>? =
        entries.filter { !it.paused }
            .mapNotNull { entry -> nextOccurrence(entry, seconds, timeZone)?.let { entry to it } }
            .minByOrNull { it.second }

    /** The list with the start [id] paused, or resumed when it is paused. Nothing else changes. */
    fun togglingPause(id: String, entries: List<ScheduledProfileSwitch>) = entries.map { if (it.id == id) it.copy(paused = !it.paused) else it }

    /** The phone's rules for a list, as sentences; empty when the list can be sent. */
    fun problems(entries: List<ScheduledProfileSwitch>, profileNames: List<String>, appName: String): List<String> {
        val problems = mutableListOf<String>()
        if (entries.size > ScheduledProfileSwitch.MAX_COUNT) {
            problems.add("There can be at most ${ScheduledProfileSwitch.MAX_COUNT} scheduled switches. Delete one before adding another.")
        }
        for (entry in sortedForWeek(entries)) {
            val problem = entryProblem(entry, entries, profileNames, appName)
            if (problem != null && problem !in problems) problems.add(problem)
        }
        return problems
    }

    /** Why one entry can not be sent, or null. */
    fun entryProblem(entry: ScheduledProfileSwitch, entries: List<ScheduledProfileSwitch>, profileNames: List<String>, appName: String): String? {
        if (profileNames.none { it.equals(entry.profile, ignoreCase = true) }) {
            return "“${entry.profile}” is not a profile in $appName. Choose another profile or delete this start."
        }
        if (entry.weekday !in 1..7) return "A start has no valid weekday."
        if (entry.time < 0 || entry.time >= SECONDS_PER_DAY || entry.time % 60 != 0) return "A start time must be a whole minute of the day."
        val other = entries.firstOrNull { it.id != entry.id && it.weekday == entry.weekday && it.time == entry.time } ?: return null
        if (other.profile.equals(entry.profile, ignoreCase = true)) {
            return "${entry.profile} starts twice on ${slotText(entry)}. Delete one of them."
        }
        val first = listOf(entry, other).minWithOrNull { a, b -> TherapyProfile.standardCompare(a.profile, b.profile) }!!
        val second = if (first.id == entry.id) other else entry
        return "${first.profile} and ${second.profile} both start on ${slotText(entry)}. Only one profile can start at a time."
    }

    fun weekdayName(weekday: Int, short: Boolean = false): String {
        if (weekday !in 1..7) return "?"
        return dayOfWeek(weekday).getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, Locale.getDefault())
    }

    /** The start time, as the profile values show their times. */
    fun timeText(seconds: Int) = ProfileValueFormat.timeText(seconds)

    /** "Saturday 13:00". */
    fun slotText(entry: ScheduledProfileSwitch, short: Boolean = false) = "${weekdayName(entry.weekday, short)} ${timeText(entry.time)}"

    /** "Weekend starts Saturday 13:00". */
    fun startText(entry: ScheduledProfileSwitch, short: Boolean = false) = "${entry.profile} starts ${slotText(entry, short)}"

    /** The second line of a row: "Paused", or how long the profile stays active. */
    fun spanText(entry: ScheduledProfileSwitch, entries: List<ScheduledProfileSwitch>): String {
        if (entry.paused) return "Paused"
        val following = following(entry, entries) ?: return "until it starts again next week"
        return "until ${slotText(following, short = true)}"
    }

    /** The footer of the list: what runs next, and which starts are paused. */
    fun footerText(list: ScheduledSwitchList, appName: String, now: Double = System.currentTimeMillis() / 1000.0): String {
        if (list.entries.isEmpty()) return "No scheduled switches. $appName keeps the active profile until someone switches."
        val sentences = mutableListOf<String>()
        next(list.entries, now, list.timeZone)?.let { sentences.add("Next: ${startText(it.first)}.") }
        val paused = list.pausedCount
        if (paused == list.entries.size) {
            sentences.add(if (paused == 1) "The start is paused." else "All starts are paused.")
        } else if (paused > 0) {
            sentences.add(if (paused == 1) "1 start paused." else "$paused starts paused.")
        }
        val unpaused = list.entries.filter { !it.paused }
        if (unpaused.size == 1) sentences.add("${unpaused[0].profile} starts again every ${slotText(unpaused[0])} after a manual switch.")
        sentences.add("A profile stays active until the next start, also after a manual switch. Paused starts do not run, and resuming one switches nothing until its next time.")
        return sentences.joinToString(" ")
    }

    /** The sentence for a profile switch: how long the switched-to profile stays active. */
    fun holdText(list: ScheduledSwitchList, now: Double = System.currentTimeMillis() / 1000.0): String? {
        val next = next(list.entries, now, list.timeZone) ?: return null
        return "It stays active until ${startText(next.first)}."
    }

    /** The sentences for deleting a profile: the scheduled switches that go with it, and what that does to the week. */
    fun deletionText(list: ScheduledSwitchList, profileName: String): String? {
        val count = list.entries.count { it.profile.equals(profileName, ignoreCase = true) }
        if (count == 0) return null
        val removed = if (count == 1) {
            "$profileName is used by 1 scheduled switch, which will be removed."
        } else {
            "$profileName is used by $count scheduled switches, which will be removed."
        }
        return "$removed At those times the profile active before stays active until the next scheduled start."
    }

    /** A note when the profile's time zone is not this phone's. */
    fun timeZoneNote(timeZone: TimeZone, now: Long = System.currentTimeMillis()): String? {
        if (timeZone.getOffset(now) == TimeZone.getDefault().getOffset(now)) return null
        val name = runCatching { ZoneId.of(timeZone.id).getDisplayName(TextStyle.FULL, Locale.getDefault()) }.getOrNull() ?: timeZone.id
        return "Times are in the profile's time zone ($name), not this phone's."
    }
}

/** One difference between the list before and after an edit. */
data class ScheduledSwitchChange(val kind: Kind, val old: ScheduledProfileSwitch?, val new: ScheduledProfileSwitch?) {
    enum class Kind { ADDED, REMOVED, CHANGED, PAUSED, RESUMED }

    val text: String
        get() {
            fun start(entry: ScheduledProfileSwitch?) = entry?.let { ScheduledSwitches.startText(it) } ?: ""
            val paused = if (new?.paused == true) " (paused)" else ""
            return when (kind) {
                Kind.ADDED -> "Added: ${start(new)}$paused"
                Kind.REMOVED -> "Removed: ${start(old)}"
                Kind.CHANGED -> "Changed: ${start(old)} → ${start(new)}$paused"
                Kind.PAUSED -> "Paused: ${start(new)}"
                Kind.RESUMED -> "Resumed: ${start(new)}"
            }
        }
}

object ScheduledSwitchDiff {
    /**
     * Entries are matched by `id`. A change of only `paused` is a pause or resume; any other change, with or
     * without `paused`, is a change.
     */
    fun changes(old: List<ScheduledProfileSwitch>, new: List<ScheduledProfileSwitch>): List<ScheduledSwitchChange> {
        val changes = mutableListOf<ScheduledSwitchChange>()
        val oldByID = LinkedHashMap<String, ScheduledProfileSwitch>()
        old.forEach { oldByID.putIfAbsent(it.id, it) }
        val newIDs = new.map { it.id }.toSet()
        for (entry in ScheduledSwitches.sortedForWeek(new)) {
            val before = oldByID[entry.id]
            if (before == null) {
                changes.add(ScheduledSwitchChange(ScheduledSwitchChange.Kind.ADDED, null, entry))
                continue
            }
            val sameStart = before.profile == entry.profile && before.weekday == entry.weekday && before.time == entry.time
            if (!sameStart) {
                changes.add(ScheduledSwitchChange(ScheduledSwitchChange.Kind.CHANGED, before, entry))
            } else if (before.paused != entry.paused) {
                changes.add(ScheduledSwitchChange(if (entry.paused) ScheduledSwitchChange.Kind.PAUSED else ScheduledSwitchChange.Kind.RESUMED, before, entry))
            }
        }
        for (entry in ScheduledSwitches.sortedForWeek(old)) {
            if (entry.id !in newIDs) changes.add(ScheduledSwitchChange(ScheduledSwitchChange.Kind.REMOVED, entry, null))
        }
        return changes
    }

    /**
     * The target profiles that start in the new list where they did not before, unpaused: added, changed or
     * resumed entries. Each name once, in week order.
     */
    fun newlyStartingProfiles(old: List<ScheduledProfileSwitch>, new: List<ScheduledProfileSwitch>): List<String> {
        val names = mutableListOf<String>()
        for (change in changes(old, new)) {
            val entry = change.new ?: continue
            if (entry.paused || change.kind == ScheduledSwitchChange.Kind.REMOVED || change.kind == ScheduledSwitchChange.Kind.PAUSED) continue
            if (entry.profile !in names) names.add(entry.profile)
        }
        return names
    }

    /**
     * The profiles that start in the new list where they did not before and give more insulin than Default
     * somewhere, compared in mg/dL whatever units each profile has.
     */
    fun moreInsulinProfiles(
        old: List<ScheduledProfileSwitch>,
        new: List<ScheduledProfileSwitch>,
        profiles: List<TherapyProfile>,
    ): List<Pair<TherapyProfile, List<ProfileInsulinIncrease>>> {
        val base = profiles.firstOrNull { it.isDefault } ?: return emptyList()
        return newlyStartingProfiles(old, new).mapNotNull { name ->
            val profile = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.takeIf { !it.isDefault } ?: return@mapNotNull null
            val increases = ProfileInsulinComparison.increases(base.values, profile.values)
            if (increases.isEmpty()) null else profile to increases
        }
    }
}
