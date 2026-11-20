package com.loopandlearn.loopfollow.settings.importexport

import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.alarm.AlarmConfiguration
import com.loopandlearn.loopfollow.helpers.GlycemicMetricMode
import com.loopandlearn.loopfollow.helpers.GlycemicOutputUnit
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.VariabilityMetricMode
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSService
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.RemoteType
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.TimeInRangeDisplayMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// MARK: - Nightscout Settings Export

data class NightscoutSettingsExport(
    val version: String,
    val url: String,
    val token: String,
    val units: String,
    val glycemicMetricMode: String,
    val glycemicOutputUnit: String,
    val timeInRangeMode: String,
    val lowLine: Double?,
    val highLine: Double?,
    val variabilityMetricMode: String,
) {
    fun applyToStorage() {
        Storage.url.value = url
        Storage.token.value = token
        Storage.units.value = units

        GlycemicMetricMode.entries.firstOrNull { it.raw == glycemicMetricMode }?.let { UnitSettingsStore.glycemicMetricMode = it }
        GlycemicOutputUnit.entries.firstOrNull { it.raw == glycemicOutputUnit }?.let { UnitSettingsStore.glycemicOutputUnit = it }
        TimeInRangeDisplayMode.entries.firstOrNull { it.raw == timeInRangeMode }?.let { UnitSettingsStore.timeInRangeMode = it }
        lowLine?.let { Storage.lowLine.value = it }
        highLine?.let { Storage.highLine.value = it }
        VariabilityMetricMode.entries.firstOrNull { it.raw == variabilityMetricMode }?.let { UnitSettingsStore.variabilityMetricMode = it }
    }

    fun hasValidSettings(): Boolean = url.isNotEmpty() && token.isNotEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("url", url)
        put("token", token)
        put("units", units)
        put("glycemicMetricMode", glycemicMetricMode)
        put("glycemicOutputUnit", glycemicOutputUnit)
        put("timeInRangeMode", timeInRangeMode)
        lowLine?.let { put("lowLine", it) }
        highLine?.let { put("highLine", it) }
        put("variabilityMetricMode", variabilityMetricMode)
    }

    companion object {
        fun fromCurrentStorage() = NightscoutSettingsExport(
            version = SettingsWire.appVersion(),
            url = Storage.url.value,
            token = Storage.token.value,
            units = Storage.units.value,
            glycemicMetricMode = UnitSettingsStore.glycemicMetricMode.raw,
            glycemicOutputUnit = UnitSettingsStore.glycemicOutputUnit.raw,
            timeInRangeMode = UnitSettingsStore.timeInRangeMode.raw,
            lowLine = Storage.lowLine.value,
            highLine = Storage.highLine.value,
            variabilityMetricMode = UnitSettingsStore.variabilityMetricMode.raw,
        )

        fun fromJson(obj: JsonObject) = NightscoutSettingsExport(
            version = obj.requireString("version"),
            url = obj.requireString("url"),
            token = obj.requireString("token"),
            units = obj.optionalString("units") ?: GlucoseDisplayUnit.MGDL.raw,
            glycemicMetricMode = obj.optionalString("glycemicMetricMode") ?: GlycemicMetricMode.GMI.raw,
            glycemicOutputUnit = obj.optionalString("glycemicOutputUnit") ?: GlycemicOutputUnit.PERCENT.raw,
            timeInRangeMode = obj.optionalString("timeInRangeMode") ?: TimeInRangeDisplayMode.TIR.raw,
            lowLine = obj.optionalDouble("lowLine"),
            highLine = obj.optionalDouble("highLine"),
            variabilityMetricMode = obj.optionalString("variabilityMetricMode") ?: VariabilityMetricMode.STD_DEVIATION.raw,
        )
    }
}

// MARK: - Dexcom Settings Export

data class DexcomSettingsExport(
    val version: String,
    val userName: String,
    val password: String,
    val server: String,
) {
    fun applyToStorage() {
        Storage.shareUserName.value = userName
        Storage.sharePassword.value = password
        Storage.shareServer.value = server
    }

    fun hasValidSettings(): Boolean = userName.isNotEmpty() && password.isNotEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("userName", userName)
        put("password", password)
        put("server", server)
    }

    companion object {
        fun fromCurrentStorage() = DexcomSettingsExport(
            version = SettingsWire.appVersion(),
            userName = Storage.shareUserName.value,
            password = Storage.sharePassword.value,
            server = Storage.shareServer.value,
        )

        fun fromJson(obj: JsonObject) = DexcomSettingsExport(
            version = obj.requireString("version"),
            userName = obj.requireString("userName"),
            password = obj.requireString("password"),
            server = obj.requireString("server"),
        )
    }
}

// MARK: - Alarm Settings Export

data class AlarmSettingsExport(
    val version: String,
    val alarms: List<Alarm>,
    val alarmConfiguration: AlarmConfiguration,
) {
    /** Imported alarms replace stored alarms with the same id; the rest are kept. */
    fun applyToStorage() {
        val importedIds = alarms.map { it.id.uppercase() }.toSet()
        val kept = Storage.alarms.value.filter { it.id.uppercase() !in importedIds }
        Storage.alarms.value = kept + alarms
        Storage.alarmConfiguration.value = alarmConfiguration
    }

    fun hasValidSettings(): Boolean = alarms.isNotEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("alarms", JsonArray(alarms.map { AlarmWire.encode(it) }))
        put("alarmConfiguration", AlarmWire.encode(alarmConfiguration))
    }

    fun encodeToJSON(): String = toJson().toString()

    companion object {
        fun fromCurrentStorage() = fromSelectedAlarms(Storage.alarms.value)

        fun fromSelectedAlarms(selectedAlarms: List<Alarm>) = AlarmSettingsExport(
            version = SettingsWire.appVersion(),
            alarms = selectedAlarms,
            alarmConfiguration = Storage.alarmConfiguration.value,
        )

        fun fromJson(obj: JsonObject): AlarmSettingsExport {
            val version = obj.requireString("version")
            val array = obj["alarms"] as? JsonArray ?: throw SettingsDecodingException("Missing key: alarms")
            val alarms = array.map { element ->
                AlarmWire.decodeAlarm(element as? JsonObject ?: throw SettingsDecodingException("Type mismatch: alarms"))
            }
            val configuration = AlarmWire.decodeConfiguration(obj.requireObject("alarmConfiguration"))
            return AlarmSettingsExport(version, alarms, configuration)
        }
    }
}

/** Alarm and alarm configuration JSON, with dates as seconds since 2001-01-01. */
internal object AlarmWire {
    private val configurationKeys = listOf(
        "overrideSystemOutputVolume", "forcedOutputVolume", "audioDuringCalls",
        "ignoreZeroBG", "autoSnoozeCGMStart", "enableVolumeButtonSnooze",
    )

    fun encode(alarm: Alarm): JsonObject {
        val obj = SettingsWire.json.encodeToJsonElement(Alarm.serializer(), alarm).jsonObject
        return convertDates(obj, listOf("snoozedUntil"), SettingsWire::epochToReference)
    }

    fun encode(configuration: AlarmConfiguration): JsonObject {
        val obj = SettingsWire.json.encodeToJsonElement(AlarmConfiguration.serializer(), configuration).jsonObject
        return convertDates(obj, listOf("snoozeUntil", "muteUntil"), SettingsWire::epochToReference)
    }

    fun decodeAlarm(obj: JsonObject): Alarm {
        val id = obj.requireString("id")
        try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            throw SettingsDecodingException("Invalid alarm id")
        }
        obj.requireString("type")
        obj.requireString("name")
        if (obj["soundFile"] == null || obj["soundFile"] is JsonNull) throw SettingsDecodingException("Missing key: soundFile")
        obj.optionalDouble("snoozedUntil")
        val converted = convertDates(obj, listOf("snoozedUntil"), SettingsWire::referenceToEpoch)
        return try {
            SettingsWire.json.decodeFromJsonElement(Alarm.serializer(), converted)
        } catch (e: Exception) {
            throw SettingsDecodingException("Invalid alarm: ${e.javaClass.simpleName}")
        }
    }

    fun decodeConfiguration(obj: JsonObject): AlarmConfiguration {
        for (key in listOf("dayStart", "nightStart")) {
            val time = obj.requireObject(key)
            time.requireInt("hour")
            time.requireInt("minute")
        }
        obj.requireBool("overrideSystemOutputVolume")
        obj.requireDouble("forcedOutputVolume")
        for (key in configurationKeys.filter { it != "overrideSystemOutputVolume" && it != "forcedOutputVolume" }) obj.requireBool(key)
        obj.optionalDouble("snoozeUntil")
        obj.optionalDouble("muteUntil")
        val converted = convertDates(obj, listOf("snoozeUntil", "muteUntil"), SettingsWire::referenceToEpoch)
        return try {
            SettingsWire.json.decodeFromJsonElement(AlarmConfiguration.serializer(), converted)
        } catch (e: Exception) {
            throw SettingsDecodingException("Invalid alarm configuration: ${e.javaClass.simpleName}")
        }
    }

    private fun convertDates(obj: JsonObject, keys: List<String>, convert: (Double) -> Double): JsonObject {
        if (keys.none { obj[it] != null }) return obj
        val fields = LinkedHashMap<String, JsonElement>(obj)
        for (key in keys) {
            val value = (fields[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: continue
            fields[key] = SettingsWire.date(convert(value))
        }
        return JsonObject(fields)
    }
}

// MARK: - Remote Settings Export

data class RemoteSettingsExport(
    val version: String,
    val remoteType: RemoteType,
    val user: String,
    val sharedSecret: String,
    val remoteApnsKey: String,
    val remoteKeyId: String,
    val teamId: String?,
    val maxBolus: Double,
    val maxCarbs: Double,
    val maxProtein: Double,
    val maxFat: Double,
    val mealWithBolus: Boolean,
    val mealWithFatProtein: Boolean,
    val productionEnvironment: Boolean,
    val loopAPNSQrCodeURL: String,
    val loopMaxFutureCarbHours: Int?,
    val device: String,
) {
    fun applyToStorage() {
        Storage.remoteType.value = remoteType
        Storage.user.value = user
        Storage.sharedSecret.value = sharedSecret
        Storage.remoteApnsKey.value = remoteApnsKey
        Storage.remoteKeyId.value = remoteKeyId
        Storage.teamId.value = teamId
        Storage.maxBolus.value = maxBolus
        Storage.maxCarbs.value = maxCarbs
        Storage.maxProtein.value = maxProtein
        Storage.maxFat.value = maxFat
        Storage.mealWithBolus.value = mealWithBolus
        Storage.mealWithFatProtein.value = mealWithFatProtein
        Storage.productionEnvironment.value = productionEnvironment
        Storage.loopAPNSQrCodeURL.value = loopAPNSQrCodeURL
        loopMaxFutureCarbHours?.let { Storage.loopMaxFutureCarbHours.value = it }

        // The device type is set from the import until Nightscout reports it.
        if (device.isNotEmpty()) {
            Storage.device.value = device
        } else {
            when (remoteType) {
                RemoteType.LOOP_APNS -> Storage.device.value = "Loop"
                RemoteType.TRC -> Storage.device.value = "Trio"
                RemoteType.NONE -> Unit
            }
        }
    }

    fun hasValidSettings(): Boolean = when (remoteType) {
        RemoteType.NONE -> true
        RemoteType.TRC -> user.isNotEmpty() && sharedSecret.isNotEmpty() && remoteApnsKey.isNotEmpty() && remoteKeyId.isNotEmpty()
        RemoteType.LOOP_APNS -> remoteKeyId.isNotEmpty() && remoteApnsKey.isNotEmpty() && teamId != null && loopAPNSQrCodeURL.isNotEmpty()
    }

    data class Compatibility(
        val isCompatible: Boolean,
        val shouldPromptForURL: Boolean,
        val shouldPromptForToken: Boolean,
        val message: String,
    )

    /** Compares the import with the stored remote settings and describes what changes. */
    fun validateCompatibilityWithCurrentStorage(): Compatibility {
        var message = ""
        val shouldPromptForURL = false
        val shouldPromptForToken = false

        val currentRemoteType = Storage.remoteType.value
        val currentUser = Storage.user.value

        if (currentRemoteType != RemoteType.NONE && currentRemoteType != remoteType) {
            message += "Remote type is changing from ${currentRemoteType.displayName} to ${remoteType.displayName}. This may affect your remote commands.\n"
        }

        if (currentUser.isNotEmpty() && currentUser != user) {
            message += "Remote user is changing from '$currentUser' to '$user'. This may affect your remote commands.\n"
        }

        if (remoteType == RemoteType.TRC || remoteType == RemoteType.LOOP_APNS) {
            val currentKeyId = Storage.remoteKeyId.value
            val currentApnsKey = Storage.remoteApnsKey.value
            if (currentKeyId.isNotEmpty() && currentKeyId != remoteKeyId) {
                message += "APNS Key ID is changing. This may affect your remote commands.\n"
            }
            if (currentApnsKey.isNotEmpty() && currentApnsKey != remoteApnsKey) {
                message += "APNS Key is changing. This may affect your remote commands.\n"
            }
        }

        if (remoteType == RemoteType.TRC) {
            val currentSharedSecret = Storage.sharedSecret.value
            if (currentSharedSecret.isNotEmpty() && currentSharedSecret != sharedSecret) {
                message += "Shared secret is changing. This may affect your remote commands.\n"
            }
        }

        if (remoteType == RemoteType.LOOP_APNS) {
            val currentTeamId = Storage.teamId.value
            val currentQrCodeURL = Storage.loopAPNSQrCodeURL.value
            if (teamId != null && currentTeamId != null && teamId != currentTeamId) {
                message += "Team ID is changing. This may affect your remote commands.\n"
            }
            if (currentQrCodeURL.isNotEmpty() && currentQrCodeURL != loopAPNSQrCodeURL) {
                message += "Loop APNS QR Code URL is changing. This may affect your remote commands.\n"
            }
        }

        if (Storage.token.value.isNotEmpty()) {
            message += "Note: This import does not include Nightscout token settings. Your current Nightscout token will be preserved.\n"
        }

        val isCompatible = !shouldPromptForURL && !shouldPromptForToken
        return Compatibility(isCompatible, shouldPromptForURL, shouldPromptForToken, message)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("remoteType", remoteType.displayName)
        put("user", user)
        put("sharedSecret", sharedSecret)
        put("remoteApnsKey", remoteApnsKey)
        put("remoteKeyId", remoteKeyId)
        teamId?.let { put("teamId", it) }
        put("maxBolus", maxBolus)
        put("maxCarbs", maxCarbs)
        put("maxProtein", maxProtein)
        put("maxFat", maxFat)
        put("mealWithBolus", mealWithBolus)
        put("mealWithFatProtein", mealWithFatProtein)
        put("productionEnvironment", productionEnvironment)
        put("loopAPNSQrCodeURL", loopAPNSQrCodeURL)
        loopMaxFutureCarbHours?.let { put("loopMaxFutureCarbHours", it) }
        put("device", device)
    }

    companion object {
        fun fromCurrentStorage() = RemoteSettingsExport(
            version = SettingsWire.appVersion(),
            remoteType = Storage.remoteType.value,
            user = Storage.user.value,
            sharedSecret = Storage.sharedSecret.value,
            remoteApnsKey = Storage.remoteApnsKey.value,
            remoteKeyId = Storage.remoteKeyId.value,
            teamId = Storage.teamId.value,
            maxBolus = Storage.maxBolus.value,
            maxCarbs = Storage.maxCarbs.value,
            maxProtein = Storage.maxProtein.value,
            maxFat = Storage.maxFat.value,
            mealWithBolus = Storage.mealWithBolus.value,
            mealWithFatProtein = Storage.mealWithFatProtein.value,
            productionEnvironment = Storage.productionEnvironment.value,
            loopAPNSQrCodeURL = Storage.loopAPNSQrCodeURL.value,
            loopMaxFutureCarbHours = Storage.loopMaxFutureCarbHours.value,
            device = Storage.device.value,
        )

        fun fromJson(obj: JsonObject): RemoteSettingsExport {
            val rawType = obj.requireString("remoteType")
            val remoteType = RemoteType.entries.firstOrNull { it.displayName == rawType }
                ?: throw SettingsDecodingException("Unknown remote type")
            return RemoteSettingsExport(
                version = obj.requireString("version"),
                remoteType = remoteType,
                user = obj.requireString("user"),
                sharedSecret = obj.requireString("sharedSecret"),
                remoteApnsKey = obj.requireString("remoteApnsKey"),
                remoteKeyId = obj.requireString("remoteKeyId"),
                teamId = obj.optionalString("teamId"),
                maxBolus = obj.requireDouble("maxBolus"),
                maxCarbs = obj.requireDouble("maxCarbs"),
                maxProtein = obj.requireDouble("maxProtein"),
                maxFat = obj.requireDouble("maxFat"),
                mealWithBolus = obj.requireBool("mealWithBolus"),
                mealWithFatProtein = obj.requireBool("mealWithFatProtein"),
                productionEnvironment = obj.requireBool("productionEnvironment"),
                loopAPNSQrCodeURL = obj.requireString("loopAPNSQrCodeURL"),
                loopMaxFutureCarbHours = obj.optionalInt("loopMaxFutureCarbHours"),
                device = obj.requireString("device"),
            )
        }
    }
}

// MARK: - APNS Settings Export

/**
 * LoopFollow's own APNs key and, when known, its Apple team. Android signs remote commands only with the Remote
 * Settings key; an imported key fills those fields when they are empty (see [RemoteKeyAdoption]).
 */
data class APNSSettingsExport(
    val version: String,
    val keyId: String,
    val apnsKey: String,
    val teamId: String? = null,
) {
    fun applyToStorage() {
        Storage.lfKeyId.value = keyId
        Storage.lfApnsKey.value = LoopAPNSService.validateAndFixAPNSKey(apnsKey)
        Storage.lfTeamId.value = teamId?.takeIf { it.isNotEmpty() }
    }

    fun hasValidSettings(): Boolean = APNsCredentialValidator.isFullyConfigured(keyId, apnsKey)

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("keyId", keyId)
        put("apnsKey", apnsKey)
        teamId?.let { put("teamId", it) }
    }

    companion object {
        fun fromCurrentStorage() = APNSSettingsExport(
            version = SettingsWire.appVersion(),
            keyId = Storage.lfKeyId.value,
            apnsKey = Storage.lfApnsKey.value,
            teamId = Storage.lfTeamId.value,
        )

        fun fromJson(obj: JsonObject) = APNSSettingsExport(
            version = obj.requireString("version"),
            keyId = obj.requireString("keyId"),
            apnsKey = obj.requireString("apnsKey"),
            teamId = obj.optionalString("teamId"),
        )
    }
}

/**
 * Whether an imported LoopFollow APNs key becomes the remote command key. The key can sign for Loop or Trio only
 * when it belongs to their Apple team, so it is taken when the remote key is empty and the teams match or either
 * team is unknown; APNs rejects a key from another team.
 */
object RemoteKeyAdoption {
    enum class Decision { ADOPT, REMOTE_KEY_SET, NO_KEY, OTHER_TEAM }

    fun decide(remoteKeyId: String, remoteApnsKey: String, lfKeyId: String, lfApnsKey: String, lfTeamId: String?, remoteTeamId: String?): Decision = when {
        remoteKeyId.isNotEmpty() || remoteApnsKey.isNotEmpty() -> Decision.REMOTE_KEY_SET
        !APNsCredentialValidator.isFullyConfigured(lfKeyId, lfApnsKey) -> Decision.NO_KEY
        !lfTeamId.isNullOrEmpty() && !remoteTeamId.isNullOrEmpty() && lfTeamId != remoteTeamId -> Decision.OTHER_TEAM
        else -> Decision.ADOPT
    }

    /** Applies the decision to storage and returns it. */
    fun apply(): Decision {
        val decision = decide(
            Storage.remoteKeyId.value, Storage.remoteApnsKey.value,
            Storage.lfKeyId.value, Storage.lfApnsKey.value, Storage.lfTeamId.value, Storage.teamId.value,
        )
        if (decision == Decision.ADOPT) {
            Storage.remoteKeyId.value = Storage.lfKeyId.value
            Storage.remoteApnsKey.value = Storage.lfApnsKey.value
        }
        return decision
    }
}

internal object APNsCredentialValidator {
    /** Apple Key IDs are exactly 10 uppercase alphanumeric characters. */
    fun isValidKeyId(keyId: String): Boolean =
        keyId.length == 10 && keyId.all { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidApnsKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.contains("-----BEGIN PRIVATE KEY-----") && trimmed.contains("-----END PRIVATE KEY-----")
    }

    fun isFullyConfigured(keyId: String, apnsKey: String): Boolean = isValidKeyId(keyId) && isValidApnsKey(apnsKey)
}

// MARK: - Combined Settings Export

data class CombinedSettingsExport(
    val version: String = SettingsMigrationManager.CURRENT_VERSION,
    val appVersion: String = SettingsWire.appVersion(),
    val nightscout: NightscoutSettingsExport? = null,
    val dexcom: DexcomSettingsExport? = null,
    val remote: RemoteSettingsExport? = null,
    val alarms: AlarmSettingsExport? = null,
    val apns: APNSSettingsExport? = null,
    val exportType: String,
    /** Seconds since 2001-01-01 UTC. */
    val timestamp: Double = SettingsWire.epochToReference(nowSeconds()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("appVersion", appVersion)
        nightscout?.let { put("nightscout", it.toJson()) }
        dexcom?.let { put("dexcom", it.toJson()) }
        remote?.let { put("remote", it.toJson()) }
        alarms?.let { put("alarms", it.toJson()) }
        apns?.let { put("apns", it.toJson()) }
        put("exportType", exportType)
        put("timestamp", SettingsWire.date(timestamp))
    }

    fun encodeToJSON(): String = toJson().toString()

    companion object {
        fun fromJson(obj: JsonObject) = CombinedSettingsExport(
            version = obj.requireString("version"),
            appVersion = obj.requireString("appVersion"),
            nightscout = obj.optionalObject("nightscout")?.let { NightscoutSettingsExport.fromJson(it) },
            dexcom = obj.optionalObject("dexcom")?.let { DexcomSettingsExport.fromJson(it) },
            remote = obj.optionalObject("remote")?.let { RemoteSettingsExport.fromJson(it) },
            alarms = obj.optionalObject("alarms")?.let { AlarmSettingsExport.fromJson(it) },
            apns = obj.optionalObject("apns")?.let { APNSSettingsExport.fromJson(it) },
            exportType = obj.requireString("exportType"),
            timestamp = obj.requireDouble("timestamp"),
        )
    }
}
