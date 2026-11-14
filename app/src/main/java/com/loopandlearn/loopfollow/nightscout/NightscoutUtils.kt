package com.loopandlearn.loopfollow.nightscout

import com.loopandlearn.loopfollow.storage.Prefs
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class NightscoutError(val description: String) {
    EMPTY_ADDRESS("The address is empty."),
    INVALID_URL("The URL is invalid."),
    NETWORK_ERROR("A network error occurred."),
    SITE_NOT_FOUND("The site was not found."),
    INVALID_TOKEN("The token is invalid."),
    TOKEN_REQUIRED("A token is required."),
    UNKNOWN("An unknown error occurred."),
}

class NightscoutException(val error: NightscoutError) : Exception(error.description)

class HttpStatusException(val code: Int) : IOException("HTTP $code")

enum class EventType(val raw: String, val endpoint: String) {
    CAGE("Site Change", "/api/v1/treatments.json"),
    SAGE("Sensor Start", "/api/v1/treatments.json"),
    SGV("sgv", "/api/v1/entries.json"),
    PROFILE("profile", "/api/v1/profiles.json"),
    TREATMENTS("treatments", "/api/v1/treatments.json"),
    DEVICE_STATUS("deviceStatus", "/api/v1/devicestatus.json"),
    IAGE("Insulin Change", "/api/v1/treatments.json"),
    DB_SIZE("dbSize", "/api/v2/properties/dbsize"),
}

object NightscoutUtils {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Strip whitespace/newlines/control characters that can sneak in via paste. */
    fun sanitizeConnectionInput(input: String): String =
        input.filter { !it.isWhitespace() && !it.isISOControl() }

    fun constructURL(baseURL: String, token: String?, endpoint: String, parameters: Map<String, String>): HttpUrl? {
        val base = baseURL.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder().encodedPath(if (endpoint.startsWith("/")) endpoint else "/$endpoint")
        builder.query(null)
        if (!token.isNullOrEmpty()) builder.addQueryParameter("token", token)
        for ((k, v) in parameters) builder.addQueryParameter(k, v)
        return builder.build()
    }

    /** GET a Nightscout endpoint and parse the JSON body. */
    suspend fun executeRequest(eventType: EventType, parameters: Map<String, String>): JsonElement {
        val url = constructURL(Storage.url.value, Storage.token.value, eventType.endpoint, parameters)
            ?: throw IOException("Failed to construct URL")
        return getJson(url)
    }

    suspend fun getJson(url: HttpUrl, headers: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty body")
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            Prefs.json.parseToJsonElement(body)
        }
    }

    data class VerifyResult(
        val error: NightscoutError?,
        val jwt: String?,
        val writeAuth: Boolean,
        val adminAuth: Boolean,
    )

    suspend fun verifyURLAndToken(): VerifyResult {
        val urlUser = Storage.url.value
        val token = Storage.token.value
        if (urlUser.isEmpty()) return VerifyResult(NightscoutError.EMPTY_ADDRESS, null, false, false)
        if (!(urlUser.startsWith("http://") || urlUser.startsWith("https://"))) {
            return VerifyResult(NightscoutError.INVALID_URL, null, false, false)
        }
        val url = statusURL(urlUser, token) ?: return VerifyResult(NightscoutError.INVALID_URL, null, false, false)

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string().orEmpty()
                            val json = runCatching { Prefs.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                            val authorized = json?.obj("authorized")
                            val jwt = authorized?.string("token")
                            val groups = authorized?.array("permissionGroups")?.mapNotNull { (it as? JsonArray)?.strings() }
                            if (jwt != null && groups != null) {
                                var write = false
                                var admin = false
                                if (groups.any { it.contains("*") }) {
                                    write = true
                                    admin = true
                                } else if (groups.any { it.contains("api:treatments:create") }) {
                                    write = true
                                }
                                VerifyResult(null, jwt, write, admin)
                            } else {
                                VerifyResult(null, null, false, false)
                            }
                        }
                        401 -> VerifyResult(
                            if (token.isEmpty()) NightscoutError.TOKEN_REQUIRED else NightscoutError.INVALID_TOKEN,
                            null, false, false,
                        )
                        else -> VerifyResult(NightscoutError.UNKNOWN, null, false, false)
                    }
                }
            } catch (e: IOException) {
                when (e) {
                    is UnknownHostException, is ConnectException -> VerifyResult(NightscoutError.SITE_NOT_FOUND, null, false, false)
                    is SocketTimeoutException -> VerifyResult(NightscoutError.NETWORK_ERROR, null, false, false)
                    else -> VerifyResult(NightscoutError.NETWORK_ERROR, null, false, false)
                }
            }
        }
    }

    private fun statusURL(base: String, token: String?): HttpUrl? {
        val encoded = if (token != null) "?token=" + java.net.URLEncoder.encode(token, "UTF-8") else ""
        return ("$base/api/v1/status.json$encoded").toHttpUrlOrNull()
    }

    /** Effective time of a treatment: `timestamp` when present, otherwise `created_at`. */
    fun treatmentDate(entry: JsonObject): Double? {
        for (key in listOf("timestamp", "created_at")) {
            val value = entry[key] ?: continue
            if (value is JsonNull) continue
            return parseDateValue(value)
        }
        return null
    }

    fun parseDateValue(value: JsonElement): Double? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString) return parseDate(p.content)
        if (p.booleanOrNull != null) return null
        val raw = p.doubleOrNull ?: return null
        // Epoch milliseconds are ~1e12; epoch seconds are ~1e9.
        return if (raw > 1e11) raw / 1000 else raw
    }

    private val utcFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }

    /**
     * Parses a Nightscout date string to epoch seconds. The time is read as UTC;
     * a trailing Z, a ±HH:MM offset and fractional seconds are dropped.
     */
    fun parseDate(rawString: String): Double? {
        var s = rawString
        if (s.endsWith("Z")) {
            s = s.dropLast(1)
        } else {
            val offset = Regex("[+\\-]\\d{2}:\\d{2}$").find(s)
            if (offset != null) s = s.removeRange(offset.range)
        }
        s = s.replace(Regex("\\.\\d+"), "")
        return try {
            val date = utcFormatter.get()!!.parse(s) ?: return null
            if (s.length != 19) null else date.time / 1000.0
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a timestamp the way the app's ISO 8601 parser does for device status
     * fields: the date and time are read as UTC, any offset and fraction ignored.
     */
    fun parseISO(raw: String): Double? {
        if (raw.length < 19) return null
        return try {
            val date = utcFormatter.get()!!.parse(raw.substring(0, 19)) ?: return null
            date.time / 1000.0
        } catch (_: Exception) {
            null
        }
    }

    fun formatISO(seconds: Double, fractional: Boolean = false): String {
        val pattern = if (fractional) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
        val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date((seconds * 1000).toLong()))
    }
}

fun JsonElement.objectList(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

