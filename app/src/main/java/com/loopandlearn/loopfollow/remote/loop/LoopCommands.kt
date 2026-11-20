package com.loopandlearn.loopfollow.remote.loop

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.remote.uninterruptedSend
import com.loopandlearn.loopfollow.storage.Storage

/**
 * Sends Loop commands that need a one-time code. Each returns null when APNs accepted the push, else the error to show.
 */
// ANDROID-DEVIATION: No command is tracked as pending until Loop replies; Android cannot receive Loop's confirmation
// push. The sending screen shows "Command Sent" and the outcome arrives through the Nightscout refresh.
object LoopCommands {
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

    /** Sends with the current one-time code and marks it used once APNs accepted the push. */
    suspend fun withOTP(description: String, send: suspend (String) -> String?): String? {
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
