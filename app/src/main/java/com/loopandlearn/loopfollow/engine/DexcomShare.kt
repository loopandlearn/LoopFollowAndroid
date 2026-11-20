package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.Prefs
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Errors from Dexcom Share. A login error carries Dexcom's own code, e.g. "AccountPasswordInvalid". */
sealed class ShareError(message: String) : Exception(message) {
    class HttpError(val cause0: Exception) : ShareError(cause0.message ?: "HTTP error")
    class LoginError(val errorCode: String) : ShareError(errorCode)
    data object FetchError : ShareError("fetchError")
    class DataError(val reason: String) : ShareError(reason)
    data object DateError : ShareError("dateError")
}

enum class KnownShareServers(val url: String) {
    US("https://share2.dexcom.com"),
    NON_US("https://shareous1.dexcom.com"),
}

/** Dexcom Share client (after Mark Wilson's ShareClient, MIT). */
class ShareClient(val username: String, val password: String, private val shareServer: String) {
    private var token: String? = null

    suspend fun fetchData(entries: Int): List<ShareGlucoseData> = fetchLastWithRetries(entries, MAX_REAUTH_ATTEMPTS)

    private suspend fun ensureToken() {
        if (token != null) return
        ShareLoginBackoff.blockedCode(username, password, shareServer)?.let { throw ShareError.LoginError(it) }
        try {
            login()
        } catch (e: ShareError.LoginError) {
            if (ShareLoginBackoff.rejectsCredentials(e.errorCode)) {
                ShareLoginBackoff.block(username, password, shareServer, e.errorCode)
                LogManager.log(
                    LogManager.Category.DEXCOM,
                    "Dexcom Share login rejected (${e.errorCode}); no further login with these credentials for 15 minutes to avoid an account lockout",
                )
            }
            throw e
        }
    }

    private suspend fun login() {
        val accountId = loginCall(
            AUTHENTICATE_PATH,
            buildJsonObject {
                put("accountName", username)
                put("password", password)
                put("applicationId", APPLICATION_ID)
            },
        )
        if (accountId == EMPTY_ID) throw ShareError.LoginError("AccountNotFound")
        token = loginCall(
            LOGIN_BY_ID_PATH,
            buildJsonObject {
                put("accountId", accountId)
                put("password", password)
                put("applicationId", APPLICATION_ID)
            },
        )
    }

    /** Success is a JSON-encoded string; failure is an object with the reason in "Code". */
    private suspend fun loginCall(path: String, body: JsonObject): String {
        val response = post(shareServer + path, body.toString())
        val decoded = runCatching { Prefs.json.parseToJsonElement(response) }.getOrNull()
            ?: throw ShareError.LoginError("unknown")
        if (decoded is JsonPrimitive && decoded.isString) return decoded.content
        val code = ((decoded as? JsonObject)?.get("Code") as? JsonPrimitive)?.content ?: "unknown"
        throw ShareError.LoginError(code)
    }

    private suspend fun fetchLastWithRetries(n: Int, remaining: Int): List<ShareGlucoseData> {
        ensureToken()
        val url = (shareServer + LATEST_GLUCOSE_PATH).toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("sessionId", token)
            ?.addQueryParameter("minutes", "1440")
            ?.addQueryParameter("maxCount", n.toString())
            ?.build() ?: throw ShareError.FetchError

        val response = post(url.toString(), null)
        val decoded = runCatching { Prefs.json.parseToJsonElement(response) }.getOrNull()
        val sgvs = decoded as? JsonArray
        if (sgvs == null) {
            if (remaining > 0) {
                token = null
                return fetchLastWithRetries(n, remaining - 1)
            }
            throw ShareError.DataError("Failed to decode SGVs as array after trying to reauth: $response")
        }

        return sgvs.map { element ->
            val sgv = element as? JsonObject ?: throw ShareError.DataError("Failed to decode an SGV record: $response")
            val glucose = (sgv["Value"] as? JsonPrimitive)?.intOrNull
            val wt = (sgv["WT"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (glucose == null || wt == null) throw ShareError.DataError("Failed to decode an SGV record: $response")
            val trendElement = sgv["Trend"] as? JsonPrimitive
            val trend: Int = if (trendElement?.isString == true) {
                // Dexcom Share encodes Trend as a string since Dec 2021.
                TREND_MAP[trendElement.content] ?: 0
            } else {
                trendElement?.intOrNull ?: throw ShareError.DataError("Failed to decode. SGV record had bad trend: $response")
            }
            val index = if (trend < 0 || trend >= TREND_TABLE.size) 0 else trend
            ShareGlucoseData(glucose, parseDate(wt), TREND_TABLE[index])
        }
    }

    /** wt looks like "/Date(1462404576000)/". */
    private fun parseDate(wt: String): Double {
        val match = Regex("\\((.*)\\)").find(wt) ?: throw ShareError.DateError
        val millis = match.groupValues[1].toDoubleOrNull() ?: throw ShareError.DateError
        return millis / 1000
    }

    private suspend fun post(url: String, json: String?): String = withContext(Dispatchers.IO) {
        try {
            val body = (json ?: "").toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url)
                .post(body)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            NightscoutUtils.client.newCall(request).execute().use { it.body?.string() ?: "" }
        } catch (e: IOException) {
            throw ShareError.HttpError(e)
        }
    }

    companion object {
        private const val USER_AGENT = "Dexcom Share/3.0.2.11 CFNetwork/711.2.23 Darwin/14.0.0"
        private const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"
        private const val AUTHENTICATE_PATH = "/ShareWebServices/Services/General/AuthenticatePublisherAccount"
        private const val LOGIN_BY_ID_PATH = "/ShareWebServices/Services/General/LoginPublisherAccountById"
        private const val LATEST_GLUCOSE_PATH = "/ShareWebServices/Services/Publisher/ReadPublisherLatestGlucoseValues"
        private const val MAX_REAUTH_ATTEMPTS = 2
        private const val EMPTY_ID = "00000000-0000-0000-0000-000000000000"

        private val TREND_MAP = mapOf(
            "" to 0, "DoubleUp" to 1, "SingleUp" to 2, "FortyFiveUp" to 3, "Flat" to 4,
            "FortyFiveDown" to 5, "SingleDown" to 6, "DoubleDown" to 7, "NotComputable" to 8, "RateOutOfRange" to 9,
        )

        private val TREND_TABLE = listOf(
            "NONE", "DoubleUp", "SingleUp", "FortyFiveUp", "Flat",
            "FortyFiveDown", "SingleDown", "DoubleDown", "NOT COMPUTABLE", "RATE OUT OF RANGE",
        )
    }
}

/**
 * Share locks an account after repeated bad logins, so a login it rejects for the account or password blocks
 * further logins with the same credentials for [INTERVAL_SECONDS]. Other failures are tried again at the next fetch.
 */
object ShareLoginBackoff {
    const val INTERVAL_SECONDS = 15 * 60.0

    private class Block(val until: Double, val code: String)

    private val blocks = mutableMapOf<String, Block>()

    private fun key(username: String, password: String, server: String) = "$server\n$username\n$password"

    /** The rejection code while logins with these credentials are blocked, else null. */
    @Synchronized
    fun blockedCode(username: String, password: String, server: String, now: Double = nowSeconds()): String? {
        val block = blocks[key(username, password, server)] ?: return null
        return if (now < block.until) block.code else null
    }

    @Synchronized
    fun block(username: String, password: String, server: String, code: String, now: Double = nowSeconds()) {
        blocks[key(username, password, server)] = Block(now + INTERVAL_SECONDS, code)
    }

    /** Share's codes for an unknown account, a wrong password or too many attempts. */
    fun rejectsCredentials(code: String) = code in setOf(
        "AccountPasswordInvalid", "AccountNotFound", "SSO_AuthenticatePasswordInvalid",
        "SSO_AuthenticateAccountNotFound", "SSO_AuthenticateMaxAttemptsExceeed", "SSO_AuthenticateMaxAttemptsExceeded",
    )
}

/** Holds the Share client built from the stored credentials. */
object DexcomShare {
    var client: ShareClient? = null
        private set

    fun configure() {
        val server = if (Storage.shareServer.value == "US") KnownShareServers.US.url else KnownShareServers.NON_US.url
        client = ShareClient(Storage.shareUserName.value, Storage.sharePassword.value, server)
    }
}
