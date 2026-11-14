package com.loopandlearn.loopfollow.remote.loop

import com.loopandlearn.loopfollow.engine.NSProfile
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.remote.uninterruptedSend
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.ObservableValue
import com.loopandlearn.loopfollow.storage.Storage
import java.security.MessageDigest
import java.util.Locale

/** A saved Loop profile from the Nightscout profile store, offered for a remote switch. */
class LoopProfile(val name: String, store: NSProfile.Store) {
    data class Item(val seconds: Int, val value: Double)
    data class RangeItem(val seconds: Int, val low: Double, val high: Double)

    val unitsAreMgdl: Boolean = store.units.lowercase() == "mg/dl"
    val basal: List<Item> = store.basal.map { Item(it.timeAsSeconds.toInt(), it.value) }
    val carbRatio: List<Item> = store.carbratio.map { Item(it.timeAsSeconds.toInt(), it.value) }
    val sensitivity: List<Item> = store.sens.map { Item(it.timeAsSeconds.toInt(), it.value) }
    val correctionRange: List<RangeItem>
    /** Sent with the switch command. Loop only switches when its saved profile has the same fingerprint. */
    val fingerprint: String

    init {
        val targetLow = (store.targetLow ?: emptyList()).map { Item(it.timeAsSeconds.toInt(), it.value) }
        val targetHigh = (store.targetHigh ?: emptyList()).map { Item(it.timeAsSeconds.toInt(), it.value) }
        correctionRange = targetLow.zip(targetHigh).map { (low, high) -> RangeItem(low.seconds, low.value, high.value) }
        fingerprint = fingerprint(basal, carbRatio, sensitivity, targetLow, targetHigh)
    }

    /** A glucose value of this profile in mg/dL. */
    fun mgdl(value: Double) = if (unitsAreMgdl) value else value * GlucoseConversion.MMOL_TO_MGDL

    /** Scheduled basal over a full day, in units. */
    val basalTotal: Double
        get() = basal.withIndex().sumOf { (i, item) ->
            val end = if (i + 1 < basal.size) basal[i + 1].seconds else 24 * 3600
            item.value * (end - item.seconds) / 3600.0
        }

    override fun equals(other: Any?) = other is LoopProfile && other.name == name && other.fingerprint == fingerprint
    override fun hashCode() = name.hashCode() * 31 + fingerprint.hashCode()

    companion object {
        /** Loop lists this in `remoteCommands` when it accepts remote profile switches. */
        const val SWITCH_COMMAND = "profile-switch"

        /** Saved profiles in a Loop profile document, by name. `Default` mirrors the active settings and is left out. */
        fun profiles(profile: NSProfile): List<LoopProfile> =
            profile.store.filter { it.key != "Default" }.map { LoopProfile(it.key, it.value) }.sortedWith { a, b -> standardCompare(a.name, b.name) }

        /**
         * Matches Loop's fingerprint of a profile store entry:
         * `basal=<seconds>:<value>,...;carbratio=...;sens=...;target_low=...;target_high=...` with values to three
         * decimals, hashed with SHA-256, first 16 hex characters.
         */
        fun fingerprint(basal: List<Item>, carbRatio: List<Item>, sensitivity: List<Item>, targetLow: List<Item>, targetHigh: List<Item>): String {
            fun encode(items: List<Item>) = items.joinToString(",") { "${it.seconds}:" + String.format(Locale.US, "%.3f", it.value) }
            val canonical = listOf(
                "basal=" + encode(basal),
                "carbratio=" + encode(carbRatio),
                "sens=" + encode(sensitivity),
                "target_low=" + encode(targetLow),
                "target_high=" + encode(targetHigh),
            ).joinToString(";")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { String.format(Locale.US, "%02x", it) }.take(16)
        }

        /** Case-insensitive order with digit runs compared by value, like Finder. */
        private fun standardCompare(a: String, b: String): Int {
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

/** The saved profiles from the latest Loop profile upload. */
class LoopProfileSet(val profiles: List<LoopProfile>, val activeName: String, val uploadedAt: Double?) {
    constructor(profile: NSProfile) : this(LoopProfile.profiles(profile), profile.defaultProfile, profile.startDate?.let { NightscoutUtils.parseDate(it) })
}

object LoopProfiles {
    val current = ObservableValue<LoopProfileSet?>(null)

    fun update(profile: NSProfile?) {
        current.value = profile?.let { LoopProfileSet(it) }
    }

    /** Loads the latest Nightscout profile document, the one the profile task reads. */
    suspend fun refresh() {
        val parameters = mapOf(
            "count" to "1",
            "find[startDate][\$lte]" to NightscoutUtils.formatISO(nowSeconds() + 60, fractional = true),
        )
        val profile = try {
            NightscoutUtils.executeRequest(EventType.PROFILE, parameters).objectList().firstOrNull()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Loop profiles, error fetching profile data: ${e.message}")
            return
        }
        profile?.let { update(NSProfile(it)) }
    }
}

/**
 * Sends Loop commands that need a one-time code. Each returns null when APNs accepted the push, else the error to show.
 */
// ANDROID-DEVIATION: No command is tracked as pending until Loop replies; Android cannot receive Loop's confirmation
// push. The sending screen shows "Command Sent" and the outcome arrives through the Nightscout refresh.
object LoopCommands {
    suspend fun sendProfileSwitch(profile: LoopProfile): String? =
        withOTP("profile switch to ${profile.name}") { LoopAPNSService.sendProfileSwitch(profile.name, profile.fingerprint, it) }

    suspend fun sendCarbDelete(carb: com.loopandlearn.loopfollow.engine.LoopCarbTreatment): String? =
        withOTP("carbs delete") { LoopAPNSService.sendCarbsDelete(carb.syncIdentifier, it) }

    /** [consumedDate] in epoch seconds. */
    suspend fun sendCarbEdit(
        carb: com.loopandlearn.loopfollow.engine.LoopCarbTreatment,
        carbsAmount: Double,
        absorptionHours: Double,
        foodType: String?,
        consumedDate: Double,
    ): String? = withOTP("carbs edit") {
        LoopAPNSService.sendCarbsEdit(carb.syncIdentifier, carbsAmount, absorptionHours, foodType, consumedDate, it)
    }

    private suspend fun withOTP(description: String, send: suspend (String) -> String?): String? {
        val qrCodeURL = Storage.loopAPNSQrCodeURL.value
        val otp = TOTPGenerator.extractOTPFromURL(qrCodeURL)
            ?: return "Invalid QR code URL. Please re-scan the QR code in settings."
        if (TOTPService.isTOTPBlocked(qrCodeURL)) return "The current one-time code was already used. Wait for the next code and try again."
        return uninterruptedSend {
            val error = send(otp)
            if (error == null) {
                TOTPService.markTOTPAsUsed(Storage.loopAPNSQrCodeURL.value)
                LogManager.log(LogManager.Category.APNS, "Loop command sent: $description")
            }
            error
        }
    }
}
