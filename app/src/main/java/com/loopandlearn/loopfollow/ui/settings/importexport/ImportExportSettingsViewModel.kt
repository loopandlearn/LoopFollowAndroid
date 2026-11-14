package com.loopandlearn.loopfollow.ui.settings.importexport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.settings.importexport.APNSSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.AlarmSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.CombinedSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.DexcomSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.LogRedactor
import com.loopandlearn.loopfollow.settings.importexport.NightscoutSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.RemoteSettingsExport
import com.loopandlearn.loopfollow.settings.importexport.SettingsMigrationManager
import com.loopandlearn.loopfollow.storage.RemoteType
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class ImportPreview(
    val nightscoutURL: String?,
    val dexcomUsername: String?,
    val remoteType: String?,
    val alarmCount: Int,
    val alarmNames: List<String>,
    val apnsKeyId: String?,
)

private class SettingsImportException(message: String) : Exception(message)

class ImportExportSettingsViewModel {
    enum class ExportType(val rawValue: String) {
        NIGHTSCOUT("Nightscout Settings"),
        DEXCOM("Dexcom Share Settings"),
        REMOTE("Remote Settings"),
        APNS("APNS Settings"),
        ALARMS("Alarm Settings"),
        ;

        val importDescription: String get() = rawValue
    }

    var isShowingQRCodeDisplay by mutableStateOf(false)
    var isShowingAlarmSelection by mutableStateOf(false)
    var qrCodeErrorMessage by mutableStateOf("")
    var qrCodeString by mutableStateOf("")
    var exportType by mutableStateOf(ExportType.NIGHTSCOUT)
    var exportedAlarmIds by mutableStateOf(emptySet<String>())
    var importPreview by mutableStateOf<ImportPreview?>(null)
    var showImportConfirmation by mutableStateOf(false)
    var pendingImportSettings by mutableStateOf<CombinedSettingsExport?>(null)
    var pendingImportSource by mutableStateOf("")
    var showImportNotFoundAlert by mutableStateOf(false)
    var importNotFoundMessage by mutableStateOf("")

    // QR code

    /** Handles a scan result; null contents means the scan was cancelled. */
    fun handleQRCodeScanResult(contents: String?) {
        if (contents != null) processImportedSettings(contents)
    }

    private fun processImportedSettings(jsonString: String) {
        try {
            LogManager.log(LogManager.Category.GENERAL, "Processing QR code data: ${LogRedactor.fingerprint(jsonString)}")
            LogManager.log(LogManager.Category.GENERAL, "QR code data converted to Data, size: ${jsonString.toByteArray(Charsets.UTF_8).size} bytes")

            try {
                val element = Json.parseToJsonElement(jsonString)
                val topLevelKeys = (element as? JsonObject)?.keys?.sorted() ?: emptyList()
                LogManager.log(LogManager.Category.GENERAL, "JSON parsing successful: keys=$topLevelKeys")
            } catch (e: Exception) {
                LogManager.log(LogManager.Category.GENERAL, "JSON parsing failed: ${e.javaClass.simpleName}")
            }

            val settings = SettingsMigrationManager.migrateSettings(jsonString)
            if (settings == null) {
                LogManager.log(LogManager.Category.GENERAL, "SettingsMigrationManager.migrateSettings returned nil")
                qrCodeErrorMessage = "Failed to decode or migrate settings from QR code"
                return
            }

            LogManager.log(
                LogManager.Category.GENERAL,
                "QR code decoded successfully. Components: nightscout=${settings.nightscout != null}, remote=${settings.remote != null}, alarms=${settings.alarms != null}, apns=${settings.apns != null}",
            )

            pendingImportSettings = settings
            pendingImportSource = "QR code"
            createImportPreview(settings)
        } catch (e: Exception) {
            val currentVersion = com.loopandlearn.loopfollow.BuildConfig.VERSION_NAME
            qrCodeErrorMessage = "Import failed. This might be due to a version change (current: $currentVersion). Please try exporting settings from the source device again."
            LogManager.log(LogManager.Category.GENERAL, "QR code import failed: ${e.javaClass.simpleName}")
        }
    }

    private fun applyImportedSettings(settings: CombinedSettingsExport, source: String) {
        val importedComponents = mutableListOf<String>()

        settings.nightscout?.let { nightscout ->
            if (NightscoutSettingsExport.fromCurrentStorage().hasValidSettings()) {
                LogManager.log(LogManager.Category.GENERAL, "Warning: Nightscout settings are already configured. Import will overwrite existing Nightscout settings.")
            }
            nightscout.applyToStorage()
            importedComponents += "Nightscout settings"
            LogManager.log(LogManager.Category.GENERAL, "Nightscout settings imported from $source (version: ${nightscout.version})")
            reconnectNightscout()
        }

        settings.dexcom?.let { dexcom ->
            if (DexcomSettingsExport.fromCurrentStorage().hasValidSettings()) {
                LogManager.log(LogManager.Category.GENERAL, "Warning: Dexcom settings are already configured. Import will overwrite existing Dexcom settings.")
            }
            dexcom.applyToStorage()
            importedComponents += "Dexcom settings"
            LogManager.log(LogManager.Category.GENERAL, "Dexcom settings imported from $source (version: ${dexcom.version})")
        }

        settings.remote?.let { remote ->
            if (!remote.hasValidSettings()) throw SettingsImportException("Invalid remote settings in $source")
            val validation = remote.validateCompatibilityWithCurrentStorage()
            if (!validation.isCompatible) throw SettingsImportException("Remote settings conflict: ${validation.message}")
            remote.applyToStorage()
            importedComponents += "Remote settings (${remote.remoteType.displayName})"
            LogManager.log(
                LogManager.Category.GENERAL,
                "Remote settings imported from $source (version: ${remote.version}, type: ${remote.remoteType.displayName}, device: ${remote.device})",
            )
        }

        settings.alarms?.let { alarms ->
            LogManager.log(LogManager.Category.GENERAL, "Attempting to import alarm settings: ${alarms.alarms.size} alarms")
            alarms.applyToStorage()
            importedComponents += "Alarm settings (${alarms.alarms.size} alarms)"
            LogManager.log(LogManager.Category.GENERAL, "Alarm settings imported from $source (version: ${alarms.version}, ${alarms.alarms.size} alarms)")
        }

        settings.apns?.let { apns ->
            if (!apns.hasValidSettings()) throw SettingsImportException("Invalid APNS settings in $source")
            apns.applyToStorage()
            importedComponents += "APNS settings"
            LogManager.log(LogManager.Category.GENERAL, "APNS settings imported from $source (version: ${apns.version}, keyId: ${apns.keyId})")
        }

        if (importedComponents.isNotEmpty()) {
            qrCodeErrorMessage = "Successfully imported: ${importedComponents.joinToString(", ")}"
        }
    }

    /** Re-checks the imported site's permissions and reloads data from it. */
    // ANDROID-DEVIATION: Android picks up an imported site only through this re-check and refresh.
    private fun reconnectNightscout() {
        MainEngine.scope.launch {
            val result = NightscoutUtils.verifyURLAndToken()
            Storage.nsWriteAuth.value = result.writeAuth
            Storage.nsAdminAuth.value = result.adminAuth
            if (result.error == null) MainEngine.refresh()
        }
    }

    /** The JSON for the selected export type, or null with [qrCodeErrorMessage] set. */
    fun generateQRCodeForExport(): String? {
        val settings: CombinedSettingsExport = when (exportType) {
            ExportType.NIGHTSCOUT -> {
                val nightscoutSettings = NightscoutSettingsExport.fromCurrentStorage()
                if (!nightscoutSettings.hasValidSettings()) {
                    qrCodeErrorMessage = "Please configure your Nightscout settings first (URL and Token)"
                    return null
                }
                CombinedSettingsExport(nightscout = nightscoutSettings, exportType = exportType.rawValue)
            }
            ExportType.REMOTE -> {
                val remoteSettings = RemoteSettingsExport.fromCurrentStorage()
                if (!remoteSettings.hasValidSettings()) {
                    val currentRemoteType = Storage.remoteType.value
                    qrCodeErrorMessage = if (currentRemoteType == RemoteType.NONE) {
                        "Please configure your Remote settings first (select a remote type and configure required fields)"
                    } else {
                        "Please complete your Remote settings configuration (check required fields for ${currentRemoteType.displayName})"
                    }
                    return null
                }
                CombinedSettingsExport(remote = remoteSettings, exportType = exportType.rawValue)
            }
            ExportType.ALARMS -> {
                val alarmSettings = AlarmSettingsExport.fromCurrentStorage()
                LogManager.log(LogManager.Category.GENERAL, "Generating alarm export: ${alarmSettings.alarms.size} alarms")
                if (!alarmSettings.hasValidSettings()) {
                    qrCodeErrorMessage = "Please configure your Alarm settings first"
                    return null
                }
                CombinedSettingsExport(alarms = alarmSettings, exportType = exportType.rawValue)
            }
            ExportType.DEXCOM -> {
                val dexcomSettings = DexcomSettingsExport.fromCurrentStorage()
                if (!dexcomSettings.hasValidSettings()) {
                    qrCodeErrorMessage = "Please configure your Dexcom Share settings first (username and password)"
                    return null
                }
                CombinedSettingsExport(dexcom = dexcomSettings, exportType = exportType.rawValue)
            }
            ExportType.APNS -> {
                val apnsSettings = APNSSettingsExport.fromCurrentStorage()
                if (!apnsSettings.hasValidSettings()) {
                    qrCodeErrorMessage = "Please configure your APNS settings first (valid APNS Key ID and APNS Key)"
                    return null
                }
                CombinedSettingsExport(apns = apnsSettings, exportType = exportType.rawValue)
            }
        }
        return settings.encodeToJSON()
    }

    fun export(type: ExportType) {
        if (type == ExportType.ALARMS) {
            showAlarmSelection()
            return
        }
        exportType = type
        generateQRCodeForExport()?.let {
            qrCodeString = it
            isShowingQRCodeDisplay = true
        }
    }

    fun showAlarmSelection() {
        exportType = ExportType.ALARMS
        isShowingAlarmSelection = true
    }

    /** Selected alarms export as a bare alarm settings object. */
    fun exportSelectedAlarms(selectedAlarms: List<Alarm>) {
        qrCodeString = AlarmSettingsExport.fromSelectedAlarms(selectedAlarms).encodeToJSON()
        isShowingQRCodeDisplay = true
        exportedAlarmIds = exportedAlarmIds + selectedAlarms.map { it.id }
        isShowingAlarmSelection = false
    }

    fun cancelAlarmSelection() {
        isShowingAlarmSelection = false
    }

    private fun createImportPreview(settings: CombinedSettingsExport) {
        val nightscoutURL = settings.nightscout?.url?.takeIf { it.isNotEmpty() }
        val dexcomUsername = settings.dexcom?.userName?.takeIf { it.isNotEmpty() }
        val remoteType = settings.remote?.remoteType?.takeIf { it != RemoteType.NONE }?.displayName
        val alarmCount = settings.alarms?.alarms?.size ?: 0
        val alarmNames = settings.alarms?.alarms?.map { it.name } ?: emptyList()
        val apnsKeyId = settings.apns?.keyId?.takeIf { it.isNotEmpty() }

        val hasAnySettings = !nightscoutURL.isNullOrEmpty() ||
            !dexcomUsername.isNullOrEmpty() ||
            (!remoteType.isNullOrEmpty() && remoteType != "None") ||
            alarmCount > 0 ||
            !apnsKeyId.isNullOrEmpty()

        LogManager.log(
            LogManager.Category.GENERAL,
            "Import preview check - nightscoutURL: ${nightscoutURL?.let { LogRedactor.url(it) } ?: "nil"}, " +
                "dexcomUsername: ${dexcomUsername?.let { LogRedactor.username(it) } ?: "nil"}, remoteType: ${remoteType ?: "nil"}, " +
                "alarmCount: $alarmCount, apnsKeyIdPresent: ${apnsKeyId != null}, hasAnySettings: $hasAnySettings",
        )

        if (hasAnySettings) {
            LogManager.log(LogManager.Category.GENERAL, "Creating import preview with settings")
            val preview = ImportPreview(nightscoutURL, dexcomUsername, remoteType, alarmCount, alarmNames, apnsKeyId)
            importPreview = preview
            LogManager.log(
                LogManager.Category.GENERAL,
                "Created importPreview - nightscoutURL: ${preview.nightscoutURL?.let { LogRedactor.url(it) } ?: "nil"}, " +
                    "remoteType: ${preview.remoteType ?: "nil"}, alarmCount: ${preview.alarmCount}",
            )
            showImportConfirmation = true
            LogManager.log(LogManager.Category.GENERAL, "Set showImportConfirmation = true")
        } else {
            LogManager.log(LogManager.Category.GENERAL, "No settings found, clearing import data")
            importNotFoundMessage = "The settings file exists but contains no valid settings to import."
            showImportNotFoundAlert = true
            showImportConfirmation = false
            importPreview = null
            pendingImportSettings = null
            pendingImportSource = ""
            LogManager.log(LogManager.Category.GENERAL, "Set showImportConfirmation = false")
        }
    }

    fun confirmImport() {
        val settings = pendingImportSettings ?: return
        try {
            applyImportedSettings(settings, pendingImportSource)
        } catch (e: Exception) {
            qrCodeErrorMessage = "Import failed: ${e.message}"
        }
        cancelImport()
    }

    fun cancelImport() {
        showImportConfirmation = false
        importPreview = null
        pendingImportSettings = null
        pendingImportSource = ""
    }
}
