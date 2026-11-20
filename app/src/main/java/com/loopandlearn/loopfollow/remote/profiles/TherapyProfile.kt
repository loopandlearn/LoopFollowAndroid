package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.engine.NSProfile
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.storage.ObservableValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** One entry of a therapy schedule: seconds from midnight and a value. */
data class ProfileScheduleItem(val seconds: Int, val value: Double)

/** One correction range entry: seconds from midnight, low and high. */
data class ProfileRangeItem(val seconds: Int, val low: Double, val high: Double)

/**
 * The four schedules of a therapy profile in the compact form remote profile commands carry:
 * `{"units", "basal": [[s, v]], "carbratio": [[s, v]], "sens": [[s, v]], "target": [[s, low, high]]}`.
 * Glucose values are in [units].
 */
data class TherapyProfileValues(
    val units: String,
    val basal: List<ProfileScheduleItem>,
    val carbRatio: List<ProfileScheduleItem>,
    val sensitivity: List<ProfileScheduleItem>,
    val target: List<ProfileRangeItem>,
) {
    val fingerprint: String
        get() = fingerprint(
            basal,
            carbRatio,
            sensitivity,
            target.map { ProfileScheduleItem(it.seconds, it.low) },
            target.map { ProfileScheduleItem(it.seconds, it.high) },
        )

    /** The compact object a command carries. */
    val json: JsonObject
        get() = buildJsonObject {
            put("units", units)
            put("basal", pairs(basal))
            put("carbratio", pairs(carbRatio))
            put("sens", pairs(sensitivity))
            put("target", buildJsonArray {
                target.forEach { add(buildJsonArray { add(JsonPrimitive(it.seconds)); add(encodedNumber(it.low)); add(encodedNumber(it.high)) }) }
            })
        }

    companion object {
        const val MGDL = "mg/dL"
        const val MMOL = "mmol/L"

        /** `mg/dL` or `mmol/L` for any spelling Nightscout uploaders use. */
        fun normalizedUnits(units: String): String = if (units.lowercase().startsWith("mmol")) MMOL else MGDL

        /**
         * The fingerprint Loop and Trio compute for a profile store entry:
         * `basal=<seconds>:<value>,...;carbratio=...;sens=...;target_low=...;target_high=...` with values to three
         * decimals in the entry's units, hashed with SHA-256, first 16 hex characters.
         */
        fun fingerprint(
            basal: List<ProfileScheduleItem>,
            carbRatio: List<ProfileScheduleItem>,
            sensitivity: List<ProfileScheduleItem>,
            targetLow: List<ProfileScheduleItem>,
            targetHigh: List<ProfileScheduleItem>,
        ): String {
            fun encode(items: List<ProfileScheduleItem>) = items.joinToString(",") { "${it.seconds}:" + String.format(Locale.US, "%.3f", it.value) }
            val canonical = listOf(
                "basal=" + encode(basal),
                "carbratio=" + encode(carbRatio),
                "sens=" + encode(sensitivity),
                "target_low=" + encode(targetLow),
                "target_high=" + encode(targetHigh),
            ).joinToString(";")
            return sha256Prefix(canonical)
        }

        /** The decimals a value carries in a command, the same precision the fingerprint uses. */
        const val ENCODED_DECIMALS = 3

        /** [value] as the decimal number a command carries: rounded to [ENCODED_DECIMALS], so JSON holds `0.7` for 0.7. */
        fun encodedNumber(value: Double): JsonPrimitive {
            var decimal = BigDecimal(String.format(Locale.US, "%.${ENCODED_DECIMALS}f", value)).stripTrailingZeros()
            if (decimal.scale() < 0) decimal = decimal.setScale(0)
            return JsonPrimitive(decimal)
        }

        private fun pairs(items: List<ProfileScheduleItem>): JsonArray = buildJsonArray {
            items.forEach { add(buildJsonArray { add(JsonPrimitive(it.seconds)); add(encodedNumber(it.value)) }) }
        }
    }
}

/** SHA-256 of [text], first 16 hex characters. */
internal fun sha256Prefix(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { String.format(Locale.US, "%02x", it) }.take(16)
}

/**
 * A weekly start of a profile: from [time] on [weekday] the profile is active until the next start.
 * The compact form the Nightscout profile document and the remote commands carry:
 * `{"id", "profile", "weekday", "time", "paused"}`, with `paused` only when true.
 */
data class ScheduledProfileSwitch(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val profile: String,
    /** 1–7, Sunday = 1. */
    val weekday: Int,
    /** Seconds from midnight in the profile time zone, on a whole minute. */
    val time: Int,
    /** A paused start does not run until it is resumed. */
    val paused: Boolean = false,
) {
    val json: JsonObject
        get() = buildJsonObject {
            put("id", id)
            put("profile", profile)
            put("weekday", weekday)
            put("time", time)
            if (paused) put("paused", true)
        }

    /** `profile=<name>;weekday=<1–7>;date=-;time=<seconds>;paused=<true|false>`. `date` is always `-`: every entry is weekly. */
    val canonicalString: String
        get() = "profile=$profile;weekday=$weekday;date=-;time=$time;paused=$paused"

    companion object {
        /** Two starts per weekday. */
        const val MAX_COUNT = 14

        /** A one-off entry (`date`), a missing field or a time that is not whole seconds reads as null. */
        fun from(element: JsonElement): ScheduledProfileSwitch? {
            val json = element as? JsonObject ?: return null
            if (json.containsKey("date")) return null
            val id = json.stringValue("id") ?: return null
            val profile = json.stringValue("profile") ?: return null
            val weekday = json.numberValue("weekday")?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) < 1e9 }?.toInt() ?: return null
            val seconds = json.numberValue("time") ?: return null
            if (seconds != Math.rint(seconds) || kotlin.math.abs(seconds) >= 1e9) return null
            val paused = when (val value = json["paused"]) {
                null -> false
                is JsonPrimitive -> if (value.isString) return null else value.content.toBooleanStrictOrNull() ?: return null
                else -> return null
            }
            return ScheduledProfileSwitch(id, profile, weekday, seconds.toInt(), paused)
        }

        /** The canonical strings of all entries, sorted and joined with `|`. `id` is identity, not content, and is left out. */
        fun canonicalString(entries: List<ScheduledProfileSwitch>): String = entries.map { it.canonicalString }.sorted().joinToString("|")

        /** The fingerprint Loop and Trio compute for the list. */
        fun fingerprint(entries: List<ScheduledProfileSwitch>): String = sha256Prefix(canonicalString(entries))

        private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.numberValue(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    }
}

/** A therapy profile from the Nightscout profile store of Loop or Trio. */
class TherapyProfile(val name: String, store: NSProfile.Store, documentUnits: String = TherapyProfileValues.MGDL) {
    val values: TherapyProfileValues

    /**
     * Fingerprint of the store entry as uploaded. Commands that act on an existing profile send it so the
     * phone only acts when its profile still has these values.
     */
    val fingerprint: String

    /** False when the correction range is missing or its lows and highs do not line up, so the values can not be edited faithfully. */
    val isWellFormed: Boolean

    init {
        fun items(entries: List<NSProfile.ScheduleEntry>) = entries.map { ProfileScheduleItem(it.timeAsSeconds.toInt(), it.value) }
        val basal = items(store.basal)
        val carbRatio = items(store.carbratio)
        val sensitivity = items(store.sens)
        val targetLow = items(store.targetLow ?: emptyList())
        val targetHigh = items(store.targetHigh ?: emptyList())
        values = TherapyProfileValues(
            TherapyProfileValues.normalizedUnits(store.units.ifBlank { documentUnits }),
            basal,
            carbRatio,
            sensitivity,
            targetLow.zip(targetHigh).map { (low, high) -> ProfileRangeItem(low.seconds, low.value, high.value) },
        )
        isWellFormed = targetLow.isNotEmpty() && targetLow.size == targetHigh.size && targetLow.zip(targetHigh).all { (l, h) -> l.seconds == h.seconds }
        fingerprint = TherapyProfileValues.fingerprint(basal, carbRatio, sensitivity, targetLow, targetHigh)
    }

    val isDefault: Boolean get() = isDefaultName(name)

    val basal get() = values.basal
    val carbRatio get() = values.carbRatio
    val sensitivity get() = values.sensitivity
    val correctionRange get() = values.target

    /** Scheduled basal over a full day, in units. */
    val basalTotal: Double get() = basalTotal(basal)

    override fun equals(other: Any?) = other is TherapyProfile && other.name == name && other.values == values && other.fingerprint == fingerprint && other.isWellFormed == isWellFormed
    override fun hashCode() = name.hashCode() * 31 + fingerprint.hashCode()

    companion object {
        /** The store key of the profile that always exists (Loop `Default`, Trio `default`), matched case-insensitively. */
        const val DEFAULT_NAME = "Default"

        fun isDefaultName(name: String) = name.equals(DEFAULT_NAME, ignoreCase = true)

        fun basalTotal(basal: List<ProfileScheduleItem>): Double = basal.withIndex().sumOf { (i, item) ->
            val end = if (i + 1 < basal.size) basal[i + 1].seconds else 24 * 3600
            item.value * (end - item.seconds) / 3600.0
        }

        /** Every profile in a profile document, Default first, then by name. Trio's `default` entry is named Default, which Trio's commands also accept. */
        fun profiles(profile: NSProfile): List<TherapyProfile> =
            profile.store
                .map { (key, store) -> TherapyProfile(if (isDefaultName(key)) DEFAULT_NAME else key, store, profile.units.ifBlank { TherapyProfileValues.MGDL }) }
                .sortedWith { a, b ->
                    if (a.isDefault != b.isDefault) {
                        if (a.isDefault) -1 else 1
                    } else {
                        standardCompare(a.name, b.name)
                    }
                }

        /** Case-insensitive order with digit runs compared by value, like Finder. */
        fun standardCompare(a: String, b: String): Int {
            val tokens = Regex("\\d+|\\D+")
            val ta = tokens.findAll(a).map { it.value }.toList()
            val tb = tokens.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ta.size, tb.size)) {
                val x = ta[i]
                val y = tb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else {
                    x.compareTo(y, ignoreCase = true)
                }
                if (c != 0) return c
            }
            return ta.size.compareTo(tb.size)
        }
    }
}

/** The profiles from the latest profile upload of Loop or Trio. */
class TherapyProfileSet(
    val profiles: List<TherapyProfile>,
    /** `defaultProfile` of the document: the active profile. */
    val activeName: String,
    val uploadedAt: Double?,
    val limits: ProfileEditLimits? = null,
    val scheduledSwitches: ScheduledSwitchList = ScheduledSwitchList.EMPTY,
) {
    constructor(profile: NSProfile) : this(
        TherapyProfile.profiles(profile),
        profile.defaultProfile,
        profile.startDate?.let { NightscoutUtils.parseDate(it) },
        (profile.json["profileEditLimits"] as? JsonObject)?.let { ProfileEditLimits.from(it) },
        ScheduledSwitchList(profile),
    )

    fun isActive(profile: TherapyProfile) = profile.name.equals(activeName, ignoreCase = true)

    val activeProfile: TherapyProfile? get() = profiles.firstOrNull { isActive(it) }

    fun profile(named: String): TherapyProfile? = profiles.firstOrNull { it.name == named }
}

/** The profiles of the latest Loop or Trio profile upload; null until one is loaded. */
object TherapyProfiles {
    val current = ObservableValue<TherapyProfileSet?>(null)

    fun update(profile: NSProfile?) {
        current.value = profile?.let { TherapyProfileSet(it) }
    }

    /** Loads the newest profile document from Nightscout; null when it can not be loaded. */
    suspend fun fetch(): TherapyProfileSet? {
        val parameters = mapOf(
            "count" to "1",
            "find[startDate][\$lte]" to NightscoutUtils.formatISO(nowSeconds() + 60, fractional = true),
        )
        val profile = try {
            NightscoutUtils.executeRequest(EventType.PROFILE, parameters).objectList().firstOrNull()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Profiles, error fetching profile data: ${e.message}")
            return null
        }
        return profile?.let { TherapyProfileSet(NSProfile(it)) }
    }

    /** Loads the newest profile document and shows it. */
    suspend fun refresh() {
        fetch()?.let { current.value = it }
    }
}
