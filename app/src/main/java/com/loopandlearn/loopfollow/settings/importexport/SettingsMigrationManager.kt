package com.loopandlearn.loopfollow.settings.importexport

import com.loopandlearn.loopfollow.helpers.LogManager

object SettingsMigrationManager {
    const val CURRENT_VERSION = "1.0"

    /** Decodes a combined export, or a single settings component wrapped as one. */
    fun migrateSettings(text: String): CombinedSettingsExport? {
        val obj = SettingsWire.parseObject(text)
        if (obj != null) {
            try {
                val settings = CombinedSettingsExport.fromJson(obj)
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded CombinedSettingsExport", isDebug = true)
                return settings
            } catch (e: SettingsDecodingException) {
                LogManager.log(LogManager.Category.GENERAL, "Failed to decode CombinedSettingsExport: ${e.javaClass.simpleName}", isDebug = true)
            }
        } else {
            LogManager.log(LogManager.Category.GENERAL, "Failed to decode CombinedSettingsExport: SettingsDecodingException", isDebug = true)
        }
        return tryDecodeIndividualComponents(obj)
    }

    private fun tryDecodeIndividualComponents(obj: kotlinx.serialization.json.JsonObject?): CombinedSettingsExport? {
        if (obj != null) {
            attempt { AlarmSettingsExport.fromJson(obj) }?.let {
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded as AlarmSettingsExport", isDebug = true)
                return CombinedSettingsExport(alarms = it, exportType = "Alarm Settings")
            }
            attempt { NightscoutSettingsExport.fromJson(obj) }?.let {
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded as NightscoutSettingsExport", isDebug = true)
                return CombinedSettingsExport(nightscout = it, exportType = "Nightscout Settings")
            }
            attempt { DexcomSettingsExport.fromJson(obj) }?.let {
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded as DexcomSettingsExport", isDebug = true)
                return CombinedSettingsExport(dexcom = it, exportType = "Dexcom Settings")
            }
            attempt { RemoteSettingsExport.fromJson(obj) }?.let {
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded as RemoteSettingsExport", isDebug = true)
                return CombinedSettingsExport(remote = it, exportType = "Remote Settings")
            }
            attempt { APNSSettingsExport.fromJson(obj) }?.let {
                LogManager.log(LogManager.Category.GENERAL, "Successfully decoded as APNSSettingsExport", isDebug = true)
                return CombinedSettingsExport(apns = it, exportType = "APNS Settings")
            }
        }
        LogManager.log(LogManager.Category.GENERAL, "Failed to decode as any known component", isDebug = true)
        return null
    }

    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (_: SettingsDecodingException) {
            null
        }
}
