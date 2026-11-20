package com.loopandlearn.loopfollow.remote.loop

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.remote.APNSDelivery
import com.loopandlearn.loopfollow.remote.RemoteCommandRefresh
import com.loopandlearn.loopfollow.remote.RemoteSetupMessage
import com.loopandlearn.loopfollow.remote.profiles.ScheduledProfileSwitch
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfileValues
import com.loopandlearn.loopfollow.remote.uninterruptedSend
import com.loopandlearn.loopfollow.settings.importexport.LogRedactor
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.util.Calendar
import java.util.Locale

private fun log(message: String) = LogManager.log(LogManager.Category.APNS, message)

private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)
private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)
private fun number(value: Double) = JsonPrimitive(BigDecimal.valueOf(value))

class LoopAPNSPayload(
    val type: Type,
    val carbsAmount: Double? = null,
    val absorptionTime: Double? = null,
    val foodType: String? = null,
    val bolusAmount: Double? = null,
    /** Epoch seconds. */
    val consumedDate: Double? = null,
    val otp: String,
) {
    enum class Type { CARBS, BOLUS }
}

/** Loop remote command payloads, matching Nightscout's loop.js format. */
// ANDROID-DEVIATION: No payload carries encrypted_return_notification or return_notification; Android cannot receive
// APNs, and Loop and its remote carb edit and profile customizations apply every command without it. Results arrive
// through the Nightscout refresh.
object LoopAPNSCommands {
    fun commandHeader(otp: String, alert: String, now: Double): MutableMap<String, JsonElement> = linkedMapOf(
        "otp" to JsonPrimitive(otp),
        "remote-address" to JsonPrimitive("LoopFollow"),
        "notes" to JsonPrimitive("Sent via LoopFollow APNS"),
        "entered-by" to JsonPrimitive("LoopFollow"),
        "sent-at" to JsonPrimitive(formatDateForAPNS(now)),
        "expiration" to JsonPrimitive(formatDateForAPNS(now + 5 * 60)),
        "alert" to JsonPrimitive(alert),
    )

    fun carbsCommandPayload(payload: LoopAPNSPayload, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val carbsAmount = payload.carbsAmount ?: 0.0
        val absorptionTime = payload.absorptionTime ?: 3.0
        val fields = commandHeader(
            payload.otp,
            "Remote Carbs Entry: ${f1(carbsAmount)} grams\nAbsorption Time: ${f1(absorptionTime)} hours",
            now,
        )
        fields["carbs-entry"] = number(carbsAmount)
        fields["absorption-time"] = number(absorptionTime)
        fields["start-time"] = JsonPrimitive(formatDateForAPNS(payload.consumedDate ?: now))
        return fields
    }

    fun bolusCommandPayload(payload: LoopAPNSPayload, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val bolusAmount = payload.bolusAmount ?: 0.0
        val fields = commandHeader(payload.otp, "Remote Bolus Entry: ${f2(bolusAmount)} U", now)
        fields["bolus-entry"] = number(bolusAmount)
        return fields
    }

    fun carbsDeleteCommandPayload(syncIdentifier: String, otp: String, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val fields = commandHeader(otp, "Remote Carbs Delete", now)
        fields["carbs-delete"] = JsonPrimitive(syncIdentifier)
        return fields
    }

    /** Keys are prefixed so an unpatched Loop rejects the command. Amount, absorption time and start time are always sent; food type only when set. */
    fun carbsEditCommandPayload(
        syncIdentifier: String,
        carbsAmount: Double,
        absorptionTimeHours: Double,
        foodType: String?,
        consumedDate: Double,
        otp: String,
        now: Double = nowSeconds(),
    ): MutableMap<String, JsonElement> {
        val fields = commandHeader(
            otp,
            "Remote Carbs Edit: ${f1(carbsAmount)} grams\nAbsorption Time: ${f1(absorptionTimeHours)} hours",
            now,
        )
        fields["carbs-edit"] = JsonPrimitive(syncIdentifier)
        fields["carbs-edit-entry"] = number(carbsAmount)
        fields["carbs-edit-absorption-time"] = number(absorptionTimeHours)
        fields["carbs-edit-start-time"] = JsonPrimitive(formatDateForAPNS(consumedDate))
        if (!foodType.isNullOrEmpty()) fields["carbs-edit-food-type"] = JsonPrimitive(foodType)
        return fields
    }

    /** Keys are prefixed so an unpatched Loop rejects the command. The fingerprint identifies the previewed profile values. */
    fun profileSwitchCommandPayload(name: String, fingerprint: String, otp: String, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val fields = commandHeader(otp, "Remote Profile Switch: $name", now)
        fields["profile-switch"] = JsonPrimitive(name)
        fields["profile-switch-fingerprint"] = JsonPrimitive(fingerprint)
        return fields
    }

    /**
     * Creates the profile [name] when [originalName] is null. Otherwise updates (and, when the names differ, renames)
     * [originalName], which Loop only changes while its values still have [baseFingerprint].
     */
    fun profileSaveCommandPayload(
        name: String,
        originalName: String?,
        baseFingerprint: String?,
        values: TherapyProfileValues,
        otp: String,
        now: Double = nowSeconds(),
    ): MutableMap<String, JsonElement> {
        val fields = commandHeader(otp, "Remote Profile Save: $name", now)
        fields["profile-save"] = JsonPrimitive(name)
        originalName?.let { fields["profile-save-original-name"] = JsonPrimitive(it) }
        baseFingerprint?.let { fields["profile-save-base-fingerprint"] = JsonPrimitive(it) }
        fields["profile-save-values"] = values.json
        return fields
    }

    fun profileDeleteCommandPayload(name: String, fingerprint: String, otp: String, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val fields = commandHeader(otp, "Remote Profile Delete: $name", now)
        fields["profile-delete"] = JsonPrimitive(name)
        fields["profile-delete-fingerprint"] = JsonPrimitive(fingerprint)
        return fields
    }

    /** Replaces the scheduled switches, which Loop only does while its list has [baseFingerprint]. */
    fun scheduledSwitchesSaveCommandPayload(
        entries: List<ScheduledProfileSwitch>,
        baseFingerprint: String,
        otp: String,
        now: Double = nowSeconds(),
    ): MutableMap<String, JsonElement> {
        val fields = commandHeader(otp, "Remote Scheduled Switches Save", now)
        fields["profile-scheduled-switches-save"] = JsonArray(entries.map { it.json })
        fields["profile-scheduled-switches-base-fingerprint"] = JsonPrimitive(baseFingerprint)
        return fields
    }

    /** Override commands need no one-time code. [duration] in seconds; 0 is indefinite. */
    fun overridePayload(presetName: String, duration: Double?, now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        var alertText = "$presetName Temporary Override"
        if (duration != null && duration > 0) {
            val hours = (duration / 3600).toInt()
            val minutes = ((duration % 3600) / 60).toInt()
            alertText += if (hours > 0) " (${hours}h ${minutes}m)" else " (${minutes}m)"
        }
        val payload = overrideHeader(alertText, now)
        payload["override-name"] = JsonPrimitive(presetName)
        if (duration != null) payload["override-duration-minutes"] = JsonPrimitive((duration / 60).toInt())
        return payload
    }

    fun cancelOverridePayload(now: Double = nowSeconds()): MutableMap<String, JsonElement> {
        val payload = overrideHeader("Cancel Temporary Override", now)
        payload["cancel-temporary-override"] = JsonPrimitive("true")
        return payload
    }

    private fun overrideHeader(alert: String, now: Double): MutableMap<String, JsonElement> = linkedMapOf(
        "remote-address" to JsonPrimitive("LoopFollow"),
        "entered-by" to JsonPrimitive("LoopFollow"),
        "sent-at" to JsonPrimitive(formatDateForAPNS(now)),
        "expiration" to JsonPrimitive(formatDateForAPNS(now + 5 * 60)),
        "alert" to JsonPrimitive(alert),
    )

    /** Wraps command fields in the push payload Loop expects (matching @parse/node-apn). */
    fun apnsBody(payload: Map<String, JsonElement>): String = buildJsonObject {
        put("aps", buildJsonObject {
            put("alert", (payload["alert"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
            put("content-available", 1)
            put("interruption-level", "time-sensitive")
        })
        for ((key, value) in payload) {
            if (key != "alert" && key != "content-available" && key != "interruption-level") put(key, value)
        }
    }.toString()

    /** ISO 8601 in UTC with milliseconds, like "2022-12-24T21:34:02.090Z". */
    fun formatDateForAPNS(seconds: Double): String = NightscoutUtils.formatISO(seconds, fractional = true)
}

/** The same minute as [seconds], with the current second and millisecond, so Nightscout never sees two entries at the same instant. */
fun dateUsingCurrentSeconds(seconds: Double): Double {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = (seconds * 1000).toLong() }
    c.set(Calendar.SECOND, now.get(Calendar.SECOND))
    c.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))
    return c.timeInMillis / 1000.0
}

/**
 * Sends Loop remote commands as APNs pushes to the Loop phone, signed with the
 * APNs key of Loop's team. All send functions return null when APNs accepted
 * the push, else the error to show.
 */
object LoopAPNSService {
    private data class Credentials(val apnsKey: String, val keyId: String, val teamId: String)

    private fun effectiveCredentials() = Credentials(Storage.remoteApnsKey.value, Storage.remoteKeyId.value, Storage.teamId.value ?: "")

    /** True when the key, QR code, device token and bundle identifier are all set. */
    fun validateSetup(): Boolean {
        val creds = effectiveCredentials()
        return creds.keyId.isNotEmpty() && creds.apnsKey.isNotEmpty() && Storage.loopAPNSQrCodeURL.value.isNotEmpty() &&
            Storage.deviceToken.value.isNotEmpty() && Storage.bundleId.value.isNotEmpty()
    }

    /** True when the Remote tab can offer Loop commands. */
    fun isConfigured(): Boolean {
        val creds = effectiveCredentials()
        return creds.keyId.isNotEmpty() && creds.apnsKey.isNotEmpty() && creds.teamId.isNotEmpty() &&
            Storage.loopAPNSQrCodeURL.value.isNotEmpty() && Storage.deviceToken.value.isNotEmpty() && Storage.bundleId.value.isNotEmpty()
    }

    private suspend fun sendRemoteCommand(payload: Map<String, JsonElement>): String? {
        val creds = effectiveCredentials()
        RemoteSetupMessage.missing(
            app = "Loop",
            apnsKey = creds.keyId.isNotEmpty() && creds.apnsKey.isNotEmpty(),
            teamId = creds.teamId.isNotEmpty(),
            device = Storage.deviceToken.value.isNotEmpty() && Storage.bundleId.value.isNotEmpty(),
            other = if (Storage.loopAPNSQrCodeURL.value.isEmpty()) listOf("the Loop QR code in Remote Settings") else emptyList(),
        )?.let { return it.also { log(it) } }
        return sendAPNSNotification(Storage.deviceToken.value, Storage.bundleId.value, creds, payload)
    }

    /** Deletes the Loop carb entry with this [syncIdentifier]. Requires the Loop remote carb edit patch. */
    suspend fun sendCarbsDelete(syncIdentifier: String, otp: String): String? {
        log("Sending carbs delete for syncIdentifier=${LogRedactor.tail(syncIdentifier)}")
        val error = sendRemoteCommand(LoopAPNSCommands.carbsDeleteCommandPayload(syncIdentifier, otp))
        if (error == null) RemoteCommandRefresh.commandSent(RemoteCommandRefresh.Change.CarbEntry)
        return error
    }

    /** Replaces the Loop carb entry with this [syncIdentifier]. Requires the Loop remote carb edit patch. [consumedDate] in epoch seconds. */
    suspend fun sendCarbsEdit(
        syncIdentifier: String,
        carbsAmount: Double,
        absorptionTimeHours: Double,
        foodType: String?,
        consumedDate: Double,
        otp: String,
    ): String? {
        log("Sending carbs edit for syncIdentifier=${LogRedactor.tail(syncIdentifier)}: ${f1(carbsAmount)}g, absorption ${f1(absorptionTimeHours)}h")
        val payload = LoopAPNSCommands.carbsEditCommandPayload(syncIdentifier, carbsAmount, absorptionTimeHours, foodType, consumedDate, otp)
        val error = sendRemoteCommand(payload)
        if (error == null) RemoteCommandRefresh.commandSent(RemoteCommandRefresh.Change.CarbEntry)
        return error
    }

    /**
     * Sends a profile command. [tooLargeMessage] explains a push over the APNs size limit; such a push is never sent.
     * [description] goes to the log.
     */
    suspend fun sendProfileCommand(payload: Map<String, JsonElement>, description: String, tooLargeMessage: ((Int) -> String)? = null): String? {
        log("Sending $description")
        if (tooLargeMessage != null) {
            val size = pushSize(payload)
            if (size > APNSDelivery.MAX_PAYLOAD_BYTES) {
                log("Command not sent: $size bytes")
                return tooLargeMessage(size)
            }
        }
        return sendRemoteCommand(payload)
    }

    /** The size of the push [payload] makes. */
    fun pushSize(payload: Map<String, JsonElement>): Int = LoopAPNSCommands.apnsBody(payload).toByteArray(Charsets.UTF_8).size

    suspend fun sendCarbsViaAPNS(payload: LoopAPNSPayload): String? {
        val finalPayload = LoopAPNSCommands.carbsCommandPayload(payload)
        log("Sending carbs: ${f1(payload.carbsAmount ?: 0.0)}g, absorption: ${f1(payload.absorptionTime ?: 3.0)}h")
        val error = sendRemoteCommand(finalPayload)
        if (error == null) RemoteCommandRefresh.commandSent(RemoteCommandRefresh.Change.Meal(payload.carbsAmount ?: 0.0, 0.0))
        return error
    }

    suspend fun sendBolusViaAPNS(payload: LoopAPNSPayload): String? {
        val finalPayload = LoopAPNSCommands.bolusCommandPayload(payload)
        log("Sending bolus: ${f2(payload.bolusAmount ?: 0.0)}U")
        val error = sendRemoteCommand(finalPayload)
        if (error == null) RemoteCommandRefresh.commandSent(RemoteCommandRefresh.Change.Bolus(payload.bolusAmount ?: 0.0))
        return error
    }

    /** [duration] in seconds; 0 activates indefinitely, null uses the preset's own duration. */
    suspend fun sendOverrideNotification(presetName: String, duration: Double?): String? =
        sendOverrideCommand(LoopAPNSCommands.overridePayload(presetName, duration))

    suspend fun sendCancelOverrideNotification(): String? = sendOverrideCommand(LoopAPNSCommands.cancelOverridePayload())

    private suspend fun sendOverrideCommand(payload: Map<String, JsonElement>): String? {
        val deviceToken = Storage.deviceToken.value
        if (deviceToken.isEmpty()) return "Device token not configured".also { log(it) }
        val bundleIdentifier = Storage.bundleId.value
        if (bundleIdentifier.isEmpty()) return "Bundle identifier not configured".also { log(it) }
        return uninterruptedSend {
            val error = sendAPNSNotification(deviceToken, bundleIdentifier, effectiveCredentials(), payload)
            if (error == null) RemoteCommandRefresh.commandSent(RemoteCommandRefresh.Change.Override)
            error
        }
    }

    private fun validateCredentials(creds: Credentials): List<String> {
        val errors = mutableListOf<String>()
        val pattern = Regex("^[A-Z0-9]{10}$")
        if (!pattern.matches(creds.keyId)) errors.add("APNS Key ID (${creds.keyId}) must be 10 uppercase alphanumeric characters.")
        if (!pattern.matches(creds.teamId)) errors.add("Team ID (${creds.teamId}) must be 10 uppercase alphanumeric characters.")
        val apnsKey = creds.apnsKey
        if (!apnsKey.contains("-----BEGIN PRIVATE KEY-----") || !apnsKey.contains("-----END PRIVATE KEY-----")) {
            errors.add("APNS Key must be a valid PEM-formatted private key.")
        } else {
            val keyData = extractKeyData(apnsKey)
            if (keyData == null) {
                errors.add("APNS Key has invalid formatting.")
            } else if (!isValidBase64(keyData)) {
                errors.add("APNS Key contains invalid Base64 key data.")
            }
        }
        return errors
    }

    private suspend fun sendAPNSNotification(deviceToken: String, bundleIdentifier: String, creds: Credentials, payload: Map<String, JsonElement>): String? {
        val validationErrors = validateCredentials(creds)
        if (validationErrors.isNotEmpty()) {
            return "Credential validation failed: ${validationErrors.joinToString(", ")}".also { log(it) }
        }
        if (!bundleIdentifier.contains(".")) log("Warning: Bundle identifier may be in wrong format: $bundleIdentifier")
        val body = try {
            LoopAPNSCommands.apnsBody(payload)
        } catch (e: Exception) {
            return "Failed to serialize APNS payload: ${e.message}".also { log(it) }
        }
        return APNSDelivery.send(body, deviceToken, bundleIdentifier, Storage.productionEnvironment.value, creds.keyId, creds.teamId, creds.apnsKey)
    }

    /** Normalizes a pasted APNs key to PEM with 64-character lines. */
    fun validateAndFixAPNSKey(key: String): String {
        var fixedKey = key.replace("\\n", "\n").trim('"', '\'')
        val begin = "-----BEGIN PRIVATE KEY-----"
        val end = "-----END PRIVATE KEY-----"

        if (!fixedKey.contains("\n")) {
            log("APNS Key missing line breaks, attempting to fix format")
            if (fixedKey.contains(begin) && fixedKey.contains(end)) {
                val beginIndex = fixedKey.indexOf(begin) + begin.length
                val endIndex = fixedKey.indexOf(end)
                if (beginIndex < endIndex) {
                    val header = fixedKey.substring(0, beginIndex)
                    val keyData = fixedKey.substring(beginIndex, endIndex)
                    val footer = fixedKey.substring(endIndex)
                    val cleanKeyData = keyData.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "")
                    log("Key data validation - Length: ${cleanKeyData.length} characters")
                    if (cleanKeyData.length != 44) log("WARNING: Key data length is ${cleanKeyData.length}, expected 44 for P-256 private key")
                    if (!isValidBase64(cleanKeyData)) log("WARNING: Key data is not valid base64")
                    val formattedKeyData = cleanKeyData.chunked(64).let { chunks ->
                        // Full 64-character lines end with a newline; a shorter last line does not.
                        chunks.joinToString("") { if (it.length == 64) it + "\n" else it }
                    }
                    fixedKey = "$header\n$formattedKeyData\n$footer"
                    log("APNS Key format fixed - added proper line breaks")
                    log("Key data length: ${cleanKeyData.length} characters")
                    log("Formatted key lines: ${formattedKeyData.split("\n").size}")
                }
            }
        } else {
            val cleanedLines = fixedKey.split(Regex("[\n\r\u000B\u000C\u0085  ]")).map { it.trim() }.filter { it.isNotEmpty() }
            if (cleanedLines.size > 2) {
                val header = cleanedLines.first()
                val footer = cleanedLines.last()
                val keyLines = cleanedLines.subList(1, cleanedLines.size - 1)
                val combinedKeyData = keyLines.joinToString("")
                log("Combined key data length: ${combinedKeyData.length} characters")
                if (combinedKeyData.length != 44) log("WARNING: Combined key data length is ${combinedKeyData.length}, expected 44 for P-256 private key")
                if (!isValidBase64(combinedKeyData)) log("WARNING: Combined key data is not valid base64")
                val clean = keyLines.joinToString("") { it.replace(" ", "").replace("\t", "") }
                fixedKey = "$header\n${clean.chunked(64).joinToString("\n")}\n$footer"
                log("APNS Key reformatted - cleaned up existing line breaks")
            }
        }
        return fixedKey
    }

    private fun extractKeyData(pem: String): String? {
        val lines = pem.split("\n")
        val start = lines.indexOf("-----BEGIN PRIVATE KEY-----")
        val end = lines.indexOf("-----END PRIVATE KEY-----")
        if (start < 0 || end < 0 || start >= end) return null
        return lines.subList(start + 1, end).joinToString("")
    }

    /** Strict base64: whole groups of four from the standard alphabet, padding only at the end. */
    private fun isValidBase64(text: String): Boolean =
        text.length % 4 == 0 && Regex("^[A-Za-z0-9+/]*={0,2}$").matches(text) &&
            runCatching { android.util.Base64.decode(text, android.util.Base64.NO_WRAP) }.isSuccess
}
