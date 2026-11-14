package com.loopandlearn.loopfollow.remote

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.util.UUID

enum class TRCCommandType(val raw: String, val displayName: String) {
    BOLUS("bolus", "Bolus"),
    TEMP_TARGET("temp_target", "Temp Target"),
    CANCEL_TEMP_TARGET("cancel_temp_target", "Cancel Temp Target"),
    MEAL("meal", "Meal"),
    START_OVERRIDE("start_override", "Start Override"),
    CANCEL_OVERRIDE("cancel_override", "Cancel Override"),
    EDIT_MEAL("edit_meal", "Edit Meal"),
    DELETE_MEAL("delete_meal", "Delete Meal"),
}

/**
 * Sends Trio Remote Control commands: an encrypted payload in an APNs push to
 * the Trio phone, signed with the APNs key of Trio's team.
 */
object TrioRemote {
    // ANDROID-DEVIATION: No command carries a return_notification block; Android cannot receive APNs, and Trio
    // applies every command without it. Results arrive through the Nightscout refresh.
    private fun payload(type: TRCCommandType, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("user", Storage.user.value)
        put("command_type", type.raw)
        put("timestamp", nowSeconds())
        build()
    }

    suspend fun startOverride(name: String) = send(TRCCommandType.START_OVERRIDE, payload(TRCCommandType.START_OVERRIDE) { put("overrideName", name) }, RemoteCommandRefresh.Change.Override)

    suspend fun cancelOverride() = send(TRCCommandType.CANCEL_OVERRIDE, payload(TRCCommandType.CANCEL_OVERRIDE), RemoteCommandRefresh.Change.Override)

    suspend fun bolus(units: Double) = send(
        TRCCommandType.BOLUS,
        payload(TRCCommandType.BOLUS) { put("bolus_amount", JsonPrimitive(BigDecimal.valueOf(units))) },
        RemoteCommandRefresh.Change.Bolus(units),
    )

    /** [targetMgdl] in mg/dL, [durationMinutes] in minutes. */
    suspend fun tempTarget(targetMgdl: Double, durationMinutes: Double) = send(
        TRCCommandType.TEMP_TARGET,
        payload(TRCCommandType.TEMP_TARGET) {
            put("target", targetMgdl.toInt())
            put("duration", durationMinutes.toInt())
        },
        RemoteCommandRefresh.Change.TempTarget,
    )

    suspend fun cancelTempTarget() = send(TRCCommandType.CANCEL_TEMP_TARGET, payload(TRCCommandType.CANCEL_TEMP_TARGET), RemoteCommandRefresh.Change.TempTarget)

    /** Grams of zero are left out; at least one nutrient must be above zero. */
    suspend fun meal(carbs: Double, protein: Double, fat: Double, bolusUnits: Double, scheduledTime: Double?): String? {
        val c = carbs.takeIf { it > 0 }?.toInt()
        val p = protein.takeIf { it > 0 }?.toInt()
        val f = fat.takeIf { it > 0 }?.toInt()
        if (c == null && p == null && f == null) return "No nutrient data provided. At least one of carbs, fat, or protein must be greater than 0."
        return send(
            TRCCommandType.MEAL,
            payload(TRCCommandType.MEAL) {
                if (bolusUnits > 0) put("bolus_amount", JsonPrimitive(BigDecimal.valueOf(bolusUnits)))
                c?.let { put("carbs", it) }
                p?.let { put("protein", it) }
                f?.let { put("fat", it) }
                scheduledTime?.let { put("scheduled_time", it) }
            },
            RemoteCommandRefresh.Change.Meal((c ?: 0).toDouble(), bolusUnits),
        )
    }

    /** Deletes the Trio meal [mealID] (Trio's meal UUID), including the fat/protein entries Trio created from it. */
    suspend fun deleteMeal(mealID: String): String? {
        val commandID = UUID.randomUUID().toString().uppercase()
        return send(
            TRCCommandType.DELETE_MEAL,
            payload(TRCCommandType.DELETE_MEAL) {
                put("command_id", commandID)
                put("meal_id", mealID)
            },
            RemoteCommandRefresh.Change.CarbEntry,
            collapseID = commandID,
        ).also { logMealCommand(it, commandID, mealID) }
    }

    /** Replaces the Trio meal [mealID] with these values; [scheduledTime] in epoch seconds. */
    suspend fun editMeal(mealID: String, carbs: Int, fat: Int, protein: Int, scheduledTime: Double): String? {
        if (!(carbs >= 0 && fat >= 0 && protein >= 0 && (carbs > 0 || fat > 0 || protein > 0))) {
            return "No nutrient data provided. At least one of carbs, fat, or protein must be greater than 0."
        }
        val commandID = UUID.randomUUID().toString().uppercase()
        return send(
            TRCCommandType.EDIT_MEAL,
            payload(TRCCommandType.EDIT_MEAL) {
                put("carbs", carbs)
                put("protein", protein)
                put("fat", fat)
                put("scheduled_time", scheduledTime)
                put("command_id", commandID)
                put("meal_id", mealID)
            },
            RemoteCommandRefresh.Change.CarbEntry,
            collapseID = commandID,
        ).also { logMealCommand(it, commandID, mealID) }
    }

    private fun logMealCommand(error: String?, commandID: String, mealID: String) {
        if (error == null) LogManager.log(LogManager.Category.APNS, "Meal command sent command_id=$commandID meal_id=$mealID")
    }

    private fun validateCredentials(keyId: String, teamId: String, apnsKey: String): List<String> {
        val errors = mutableListOf<String>()
        val pattern = Regex("^[A-Z0-9]{10}$")
        if (!pattern.matches(keyId)) errors.add("APNS Key ID ($keyId) must be 10 uppercase alphanumeric characters.")
        if (!pattern.matches(teamId)) errors.add("Team ID ($teamId) must be 10 uppercase alphanumeric characters.")
        if (!apnsKey.contains("-----BEGIN PRIVATE KEY-----") || !apnsKey.contains("-----END PRIVATE KEY-----")) {
            errors.add("APNS Key must be a valid PEM-formatted private key.")
        } else {
            val lines = apnsKey.split("\n")
            val start = lines.indexOf("-----BEGIN PRIVATE KEY-----")
            val end = lines.indexOf("-----END PRIVATE KEY-----")
            if (start < 0 || end <= start) {
                errors.add("APNS Key has invalid formatting.")
            } else if (runCatching { android.util.Base64.decode(lines.subList(start + 1, end).joinToString(""), android.util.Base64.DEFAULT) }.isFailure) {
                errors.add("APNS Key contains invalid Base64 key data.")
            }
        }
        return errors
    }

    /** Returns null when APNs accepted the push, else the error to show. */
    private suspend fun send(type: TRCCommandType, payload: JsonObject, change: RemoteCommandRefresh.Change, collapseID: String = type.raw): String? {
        val deviceToken = Storage.deviceToken.value
        val sharedSecret = Storage.sharedSecret.value
        val user = Storage.user.value
        val bundleId = Storage.bundleId.value
        val apnsKey = Storage.remoteApnsKey.value
        val keyId = Storage.remoteKeyId.value
        val teamId = Storage.teamId.value ?: ""

        val missing = buildList {
            if (sharedSecret.isEmpty()) add("sharedSecret")
            if (apnsKey.isEmpty()) add("apnsKey")
            if (keyId.isEmpty()) add("keyId")
            if (user.isEmpty()) add("user")
            if (deviceToken.isEmpty()) add("deviceToken")
            if (bundleId.isEmpty()) add("bundleId")
            if (teamId.isEmpty()) add("teamId")
        }
        if (missing.isNotEmpty()) {
            return "Missing required fields for command: ${missing.joinToString(", ")}".also { LogManager.log(LogManager.Category.APNS, it) }
        }
        val errors = validateCredentials(keyId, teamId, apnsKey)
        if (errors.isNotEmpty()) {
            return "Credential validation failed: ${errors.joinToString(", ")}".also { LogManager.log(LogManager.Category.APNS, it) }
        }

        val encrypted = try {
            SecureMessenger(sharedSecret).encrypt(payload.toString())
        } catch (e: Exception) {
            return "Failed to encode or encrypt push message: ${e.message}".also { LogManager.log(LogManager.Category.APNS, it) }
        }
        val message = buildJsonObject {
            put("aps", buildJsonObject {
                put("content-available", 1)
                put("interruption-level", "time-sensitive")
                put("alert", "Remote Command: ${type.displayName}")
            })
            put("encrypted_data", encrypted)
        }
        return uninterruptedSend {
            val error = APNSDelivery.send(
                message.toString(), deviceToken, bundleId, Storage.productionEnvironment.value,
                keyId, teamId, apnsKey, expiration = "600", collapseID = collapseID,
            )
            if (error == null) RemoteCommandRefresh.commandSent(change)
            error
        }
    }
}
