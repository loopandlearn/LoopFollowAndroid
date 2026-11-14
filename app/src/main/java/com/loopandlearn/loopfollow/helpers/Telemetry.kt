package com.loopandlearn.loopfollow.helpers

import android.os.Build
import com.loopandlearn.loopfollow.BuildConfig
import com.loopandlearn.loopfollow.engine.TaskID
import com.loopandlearn.loopfollow.engine.TaskScheduler
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.storageValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object TelemetryClient {
    private const val ENDPOINT = "https://lf.bjorkert.se/api/telemetry/checkin"
    private const val WEEKLY_INTERVAL = 7 * 24 * 60 * 60 * 1000L
    private const val DAILY_INTERVAL = 24 * 60 * 60 * 1000L
    private const val RETRY_DELAY = 60 * 1000L

    // ANDROID-DEVIATION: Debug builds can point check-ins at a local test server; release builds always use lf.bjorkert.se.
    private val debugURL = storageValue("debugTelemetryURL", "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        NightscoutUtils.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    private val endpoint: String
        get() = if (BuildConfig.DEBUG && debugURL.value.isNotEmpty()) debugURL.value else ENDPOINT

    /**
     * Static write token, committed in source; the same public string the iOS app
     * ships. The backend treats it as a "front door sign", not as a secret:
     * TLS, an NGINX rate limit, strict schema validation and an insert+find-only
     * database role bound any abuse to harmless duplicate-row inserts.
     */
    private const val writeToken = "RsEDJ8RoOs7HHZ_XGOdI1sY3Yuv6iPnRRk7tg-NlCAg"

    /**
     * Identifies the running build for the after-update ping.
     *
     * ANDROID-DEVIATION: Version name, version code and build date identify the
     * build; the Android build carries no commit SHA.
     */
    val buildIdentifier: String
        get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_DATE}"

    /**
     * Records a cold launch in a sliding 7-day window of timestamps. Called on
     * every process start (activity, service or alarm). The count in the window
     * is sent as `coldLaunches7d`.
     */
    fun recordColdLaunch(now: Long = System.currentTimeMillis()) {
        val cutoff = now - WEEKLY_INTERVAL
        Storage.telemetryColdLaunchTimes.value = Storage.telemetryColdLaunchTimes.value.filter { it >= cutoff } + now
    }

    /** True when the running build differs from the one recorded at the last successful send. */
    fun buildChangedSinceLastSend(): Boolean = Storage.telemetryLastSentBuild.value != buildIdentifier

    /**
     * Called on every process start: records the cold launch, sends one ping
     * right away after an app update, then starts the daily schedule.
     *
     * ANDROID-DEVIATION: Runs straight from Application.onCreate with no
     * storage-readiness wait; the app is not direct-boot aware, so its process
     * only starts once the device has been unlocked and preferences are readable.
     */
    fun start() {
        recordColdLaunch()
        scope.launch {
            if (buildChangedSinceLastSend()) maybeSend()
            scheduleRecurring()
        }
    }

    /**
     * Wires telemetry into [TaskScheduler]. The next run is 24 hours after the
     * last successful send, or a minute from now when nothing has been sent yet.
     * Each fired tick reschedules itself. Does nothing until consent is given,
     * or in a build that has nowhere to send.
     *
     * ANDROID-DEVIATION: The next run is never less than a minute away, so a
     * send that keeps failing while the daily ping is overdue (for example with
     * no network) retries once a minute.
     */
    fun scheduleRecurring() {
        if (!Storage.telemetryConsentDecisionMade.value || !Storage.telemetryEnabled.value) return

        val now = System.currentTimeMillis()
        val last = Storage.telemetryLastSentAt.value
        val nextRun = maxOf(if (last != null) last + DAILY_INTERVAL else now, now + RETRY_DELAY)

        TaskScheduler.scheduleTask(TaskID.TELEMETRY, nextRun) {
            scope.launch {
                maybeSend()
                scheduleRecurring()
            }
        }
    }

    /** Records the consent decision; a yes sends the first ping at once and starts the daily cadence. */
    fun recordDecision(enabled: Boolean) {
        Storage.telemetryEnabled.value = enabled
        Storage.telemetryConsentDecisionMade.value = true
        if (enabled) {
            scope.launch {
                maybeSend()
                scheduleRecurring()
            }
        }
    }

    /** Sends when consent is given and the toggle is on; when to send is the caller's decision. */
    suspend fun maybeSend() {
        if (!Storage.telemetryConsentDecisionMade.value) return
        if (!Storage.telemetryEnabled.value) return
        send()
    }

    /** Stable random identifier for this install, sent as `idfv`. Created on first use. */
    private fun installId(): String {
        val existing = Storage.telemetryInstallId.value
        if (existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString().uppercase()
        Storage.telemetryInstallId.value = created
        return created
    }

    /** The exact payload that would be POSTed right now; also shown in "What's sent". */
    fun buildPayload(): JsonObject = buildJsonObject {
        put("appVersion", BuildConfig.VERSION_NAME)
        put("buildDate", BuildConfig.BUILD_DATE)

        // ANDROID-DEVIATION: No `isTestFlight`, `contactEnabled` or `calendarEnabled`; Android has no TestFlight builds and no contact or calendar integration.

        put("instance", if (BuildConfig.APP_SUFFIX.isEmpty()) "LoopFollow" else "LoopFollow_${BuildConfig.APP_SUFFIX}")

        // ANDROID-DEVIATION: `idfv` is a random UUID created by LoopFollow and kept in app storage; Android has no per-vendor identifier.
        put("idfv", installId())

        put("device", hardwareIdentifier())
        put("platform", "Android")
        put("osVersion", Build.VERSION.RELEASE)

        put("usesDexcom", Storage.shareUserName.value.trim().isNotEmpty())
        put("usesNightscout", Storage.url.value.trim().isNotEmpty())

        // Field omitted when the followed app hasn't been detected yet.
        val device = Storage.device.value.trim()
        if (device.isNotEmpty()) put("followingApp", device)

        put("backgroundRefreshMethod", serialName(Storage.backgroundRefreshType.value))

        // Selected user-preference fields; none reveal personal or health information.
        put("units", Storage.units.value)
        put("remoteType", serialName(Storage.remoteType.value))
        put("appearanceMode", serialName(Storage.appearanceMode.value))

        put("coldLaunches7d", Storage.telemetryColdLaunchTimes.value.size)
    }

    /** The payload as indented JSON with sorted keys. */
    fun renderPayload(): String = prettyJson.encodeToString(JsonObject.serializer(), JsonObject(buildPayload().toSortedMap()))

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Builds the payload, POSTs it and records the send on 2xx. Errors are logged at debug level only. */
    suspend fun send() {
        val body = Json.encodeToString(JsonObject.serializer(), buildPayload())
        try {
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $writeToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Storage.telemetryLastSentAt.value = System.currentTimeMillis()
                    Storage.telemetryLastSentBuild.value = buildIdentifier
                    LogManager.log(LogManager.Category.TELEMETRY, "send ok status=${response.code}", isDebug = true)
                } else {
                    LogManager.log(LogManager.Category.TELEMETRY, "send non-2xx status=${response.code}", isDebug = true)
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.TELEMETRY, "send error: ${e.message}", isDebug = true)
        }
    }

    // ANDROID-DEVIATION: manufacturer and model, prefixed with "Simulator " on the emulator, which is the prefix the
    // telemetry server excludes from analysis.
    private fun hardwareIdentifier(): String {
        val name = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifEmpty { "Unknown" }
        return if (isEmulator()) "Simulator $name" else name
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.startsWith("sdk") || Build.PRODUCT.contains("emulator")

    /** The value an enum stores under, which matches the iOS raw value. */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T : Enum<T>> serialName(value: T): String {
        val s: KSerializer<T> = serializer()
        return s.descriptor.getElementName(value.ordinal)
    }
}
