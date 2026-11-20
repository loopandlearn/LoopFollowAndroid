package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.helpers.Localizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The bounds Loop or Trio publishes in its profile document (`profileEditLimits`) for remotely edited profiles.
 * Glucose values and steps are in [units]. LoopFollow offers only values inside these bounds.
 */
data class ProfileEditLimits(
    val units: String,
    val maxBasal: Double,
    val basalRates: List<Double>,
    val basalMinimum: Double?,
    val carbRatio: Range,
    val sensitivity: Range,
    val target: Range,
    val maxSegments: Int,
    /** The pump's basal schedule entry limit, when the app publishes one. */
    val maxBasalSegments: Int?,
    val segmentSeconds: Int,
) {
    data class Range(val min: Double, val max: Double, val step: Double, val recommendedMin: Double?, val recommendedMax: Double?) {
        val isUsable: Boolean
            get() = min.isFinite() && max.isFinite() && step.isFinite() && step > 0 && min <= max && (max - min) / step <= 20000

        /** Every value from [min] to [max] in steps of [step], rounded to the step's decimals. */
        val values: List<Double>
            get() {
                if (!isUsable) return emptyList()
                val decimals = decimals(step)
                val count = floor((max - min) / step + 1e-9).toInt()
                return (0..count).map { round(min + it * step, decimals) }
            }

        fun contains(value: Double) = value >= min - TOLERANCE && value <= max + TOLERANCE

        /** True when the value is inside the hard bounds but outside the recommended ones. */
        fun isOutsideRecommended(value: Double): Boolean {
            if (recommendedMin != null && value < recommendedMin - TOLERANCE) return true
            if (recommendedMax != null && value > recommendedMax + TOLERANCE) return true
            return false
        }

        companion object {
            internal fun from(json: JsonObject): Range = Range(
                json.required("min"),
                json.required("max"),
                json.required("step"),
                json.optional("recommendedMin"),
                json.optional("recommendedMax"),
            )
        }
    }

    /** Basal rates the editor offers: the supported rates up to max basal, from [basalMinimum] when set. */
    val allowedBasalRates: List<Double>
        get() = basalRates
            .filter { it <= maxBasal + TOLERANCE && it >= (basalMinimum ?: Double.NEGATIVE_INFINITY) - TOLERANCE }
            .sorted()

    fun isAllowedBasalRate(rate: Double) = allowedBasalRates.any { abs(it - rate) < TOLERANCE }

    /** The most segments a schedule may have: the published maximum, never more than one per segment slot. */
    val segmentLimit: Int get() = minOf(maxSegments, 86400 / segmentSeconds)

    /** The most segments a schedule of [kind] may have; basal also stays within the pump's entry limit. */
    fun segmentLimit(kind: ProfileScheduleKind): Int =
        if (kind == ProfileScheduleKind.BASAL && maxBasalSegments != null) minOf(segmentLimit, maxBasalSegments) else segmentLimit

    /** False when the published limits are inconsistent; the editor is then disabled. */
    val isUsable: Boolean
        get() = segmentSeconds > 0 && 86400 % segmentSeconds == 0 && maxSegments > 0 && (maxBasalSegments ?: 1) > 0 &&
            allowedBasalRates.isNotEmpty() && carbRatio.isUsable && sensitivity.isUsable && target.isUsable

    val normalizedUnits: String get() = TherapyProfileValues.normalizedUnits(units)

    fun status(value: Double, kind: ProfileScheduleKind): ProfileValueStatus {
        if (!value.isFinite()) return ProfileValueStatus.NOT_ALLOWED
        val range = kind.range(this) ?: return if (isAllowedBasalRate(value)) ProfileValueStatus.OK else ProfileValueStatus.NOT_ALLOWED
        if (!range.contains(value)) return ProfileValueStatus.NOT_ALLOWED
        return if (range.isOutsideRecommended(value)) ProfileValueStatus.OUTSIDE_RECOMMENDED else ProfileValueStatus.OK
    }

    /** Plain-language bounds for a schedule, for footers and messages. */
    fun boundsText(kind: ProfileScheduleKind): String {
        val units = kind.unitText(normalizedUnits)
        val decimals = kind.decimals(this)
        fun text(value: Double) = Localizer.formatToLocalizedString(value, maxOf(decimals, 3), decimals)
        val range = kind.range(this)
        if (range == null) {
            val rates = allowedBasalRates
            return "Allowed: ${text(rates.firstOrNull() ?: 0.0)}–${text(rates.lastOrNull() ?: 0.0)} $units, in the steps the pump supports."
        }
        var result = "Allowed: ${text(range.min)}–${text(range.max)} $units."
        if (range.recommendedMin != null || range.recommendedMax != null) {
            val low = range.recommendedMin?.let(::text) ?: text(range.min)
            val high = range.recommendedMax?.let(::text) ?: text(range.max)
            result += " Values outside $low–$high are unusual and shown in orange."
        }
        return result
    }

    companion object {
        const val TOLERANCE = 1e-9

        /** The limits in a profile document, or null when they are missing or malformed. */
        fun from(json: JsonObject): ProfileEditLimits? = try {
            ProfileEditLimits(
                units = (json["units"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Malformed(),
                maxBasal = json.required("maxBasal"),
                basalRates = (json["basalRates"] as? JsonArray ?: throw Malformed()).map { (it as? JsonPrimitive)?.numberOrNull() ?: throw Malformed() },
                basalMinimum = json.optional("basalMinimum"),
                carbRatio = Range.from(json["carbRatio"] as? JsonObject ?: throw Malformed()),
                sensitivity = Range.from(json["sensitivity"] as? JsonObject ?: throw Malformed()),
                target = Range.from(json["target"] as? JsonObject ?: throw Malformed()),
                maxSegments = json.requiredInt("maxSegments"),
                maxBasalSegments = json.optional("maxBasalSegments")?.let { it.toWholeInt() ?: throw Malformed() },
                segmentSeconds = json.requiredInt("segmentSeconds"),
            )
        } catch (e: Malformed) {
            null
        }

        /** The number of decimals [step] needs, at most 4. */
        fun decimals(step: Double): Int {
            for (decimals in 0..4) {
                val scaled = step * 10.0.pow(decimals)
                if (abs(scaled - Math.rint(scaled)) < 1e-6) return decimals
            }
            return 4
        }

        fun round(value: Double, decimals: Int): Double {
            val factor = 10.0.pow(decimals)
            return (value * factor).roundToLong() / factor
        }
    }
}

private class Malformed : Exception()

private fun JsonPrimitive.numberOrNull(): Double? = if (isString) null else content.toDoubleOrNull()

private fun Double.toWholeInt(): Int? = if (this == Math.rint(this) && abs(this) < Int.MAX_VALUE) toInt() else null

private fun JsonObject.required(key: String): Double = (this[key] as? JsonPrimitive)?.numberOrNull() ?: throw Malformed()

private fun JsonObject.requiredInt(key: String): Int = required(key).toWholeInt() ?: throw Malformed()

/** Null when missing or JSON null; a value of another type is malformed. */
private fun JsonObject.optional(key: String): Double? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.numberOrNull() ?: throw Malformed()
}

/** The four schedules of a therapy profile. */
enum class ProfileScheduleKind(val title: String) {
    CORRECTION_RANGE("Correction Range"),
    CARB_RATIO("Carb Ratios"),
    BASAL("Basal Rates"),
    SENSITIVITY("Insulin Sensitivities"),
    ;

    val isRange: Boolean get() = this == CORRECTION_RANGE

    /** Correction ranges and insulin sensitivities are in the profile's glucose units. */
    val isGlucose: Boolean get() = this == CORRECTION_RANGE || this == SENSITIVITY

    fun unitText(glucoseUnits: String): String = when (this) {
        CORRECTION_RANGE -> glucoseUnits
        CARB_RATIO -> "g/U"
        BASAL -> "U/hr"
        SENSITIVITY -> "$glucoseUnits/U"
    }

    /** The bounds for this schedule; null for basal, which uses the list of supported rates. */
    fun range(limits: ProfileEditLimits): ProfileEditLimits.Range? = when (this) {
        CORRECTION_RANGE -> limits.target
        CARB_RATIO -> limits.carbRatio
        SENSITIVITY -> limits.sensitivity
        BASAL -> null
    }

    /** The values the editor offers, in ascending order. */
    fun options(limits: ProfileEditLimits): List<Double> = range(limits)?.values ?: limits.allowedBasalRates

    fun decimals(limits: ProfileEditLimits): Int {
        range(limits)?.let { return ProfileEditLimits.decimals(it.step) }
        return limits.allowedBasalRates.maxOfOrNull { ProfileEditLimits.decimals(it) } ?: 2
    }

    /** The direction of this schedule's values that gives more insulin. */
    val moreInsulinWord: String get() = if (this == BASAL) "higher" else "lower"
}

/** How a value compares with the published limits. */
enum class ProfileValueStatus {
    OK,

    /** Allowed, but outside the recommended range: shown as a warning. */
    OUTSIDE_RECOMMENDED,

    /** Outside the hard limits (or not a supported basal rate): the profile can not be sent. */
    NOT_ALLOWED,
}

object ProfileValidation {
    const val MAX_NAME_LENGTH = 32

    /** The most profiles Loop and Trio keep, Default included. */
    const val MAX_PROFILES = 20

    /** Why another profile can not be created, or null when it can. */
    fun countProblem(profileCount: Int): String? =
        if (profileCount >= MAX_PROFILES) "There are already $MAX_PROFILES profiles, the most the phone keeps. Delete a profile before creating another." else null

    /** The name as it is sent: surrounding spaces removed. */
    fun normalizedName(name: String) = name.trim()

    /**
     * Why [name] can not be used for a new or renamed profile, or null when it can.
     * [originalName] is the profile being renamed, which may keep its own name in another letter case.
     */
    fun nameProblem(name: String, existingNames: List<String>, originalName: String? = null): String? {
        val trimmed = normalizedName(name)
        if (trimmed.isEmpty()) return "Enter a name."
        if (trimmed.codePointCount(0, trimmed.length) > MAX_NAME_LENGTH) return "Use at most $MAX_NAME_LENGTH characters."
        if (trimmed.contains(".")) return "A name can't contain a period (.)."
        if (trimmed.startsWith("$")) return "A name can't start with $."
        if (TherapyProfile.isDefaultName(trimmed)) return "“Default” is the name of the Default profile. Choose another name."
        if (existingNames.any { it.equals(trimmed, ignoreCase = true) && it != originalName }) return "A profile with this name already exists."
        return null
    }

    /** Everything that keeps [values] from being sent within [limits]. Empty when the profile can be sent. */
    fun problems(values: TherapyProfileValues, limits: ProfileEditLimits): List<String> {
        val problems = mutableListOf<String>()
        if (values.units != limits.normalizedUnits) {
            problems.add("The profile is in ${values.units}, but the limits are in ${limits.normalizedUnits}.")
        }
        val schedules = listOf(
            ProfileScheduleKind.CORRECTION_RANGE to values.target.map { it.seconds },
            ProfileScheduleKind.CARB_RATIO to values.carbRatio.map { it.seconds },
            ProfileScheduleKind.BASAL to values.basal.map { it.seconds },
            ProfileScheduleKind.SENSITIVITY to values.sensitivity.map { it.seconds },
        )
        for ((kind, starts) in schedules) problems += scheduleProblems(starts, kind, limits)
        for ((kind, items) in listOf(
            ProfileScheduleKind.CARB_RATIO to values.carbRatio,
            ProfileScheduleKind.BASAL to values.basal,
            ProfileScheduleKind.SENSITIVITY to values.sensitivity,
        )) {
            if (items.any { limits.status(it.value, kind) == ProfileValueStatus.NOT_ALLOWED }) {
                problems.add("${kind.title}: a value is outside what is allowed. ${limits.boundsText(kind)}")
            }
        }
        val range = ProfileScheduleKind.CORRECTION_RANGE
        if (values.target.any { limits.status(it.low, range) == ProfileValueStatus.NOT_ALLOWED || limits.status(it.high, range) == ProfileValueStatus.NOT_ALLOWED }) {
            problems.add("Correction Range: a value is outside what is allowed. ${limits.boundsText(range)}")
        }
        if (values.target.any { it.low > it.high + ProfileEditLimits.TOLERANCE }) {
            problems.add("Correction Range: the low value must not be above the high value.")
        }
        return problems
    }

    /**
     * Everything that keeps [command] from being sent within [limits]. A save that keeps the values of the
     * profile it updates (a pure rename) is not checked against the limits, which may have changed since the
     * values were stored.
     */
    fun problems(command: ProfileCommand, limits: ProfileEditLimits): List<String> {
        if (command !is ProfileCommand.Save) return emptyList()
        if (command.original != null && command.original.values == command.values) return emptyList()
        return problems(command.values, limits)
    }

    fun scheduleProblems(starts: List<Int>, kind: ProfileScheduleKind, limits: ProfileEditLimits): List<String> {
        val first = starts.firstOrNull() ?: return listOf("${kind.title}: add at least one time.")
        val problems = mutableListOf<String>()
        if (first != 0) problems.add("${kind.title}: the first time must be midnight.")
        if (starts.zipWithNext().any { (a, b) -> a >= b }) problems.add("${kind.title}: each time must be later than the one before.")
        if (starts.any { it < 0 || it >= 86400 || it % limits.segmentSeconds != 0 }) {
            problems.add("${kind.title}: times must be on the ${limits.segmentSeconds / 60}-minute grid.")
        }
        val segmentLimit = limits.segmentLimit(kind)
        if (starts.size > segmentLimit) problems.add("${kind.title}: at most $segmentLimit times are allowed.")
        return problems
    }
}
