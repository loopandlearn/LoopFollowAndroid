package com.loopandlearn.loopfollow.nightscout

import com.loopandlearn.loopfollow.storage.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Creates a read-only Nightscout access token from the site's API secret. The
 * secret only authorizes these requests and is never stored.
 */
object TokenProvisioning {
    const val SUBJECT_NAME = "LoopFollow"

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun baseURL(url: String): HttpUrl {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw NightscoutException(NightscoutError.EMPTY_ADDRESS)
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) throw NightscoutException(NightscoutError.INVALID_URL)
        return trimmed.toHttpUrlOrNull() ?: throw NightscoutException(NightscoutError.INVALID_URL)
    }

    private fun subjectsURL(base: HttpUrl) = base.newBuilder().addPathSegments("api/v2/authorization/subjects").build()

    private fun validate(response: Response) {
        when (response.code) {
            in 200..299 -> return
            401, 403 -> throw NightscoutException(NightscoutError.INVALID_TOKEN)
            404 -> throw NightscoutException(NightscoutError.SITE_NOT_FOUND)
            else -> throw NightscoutException(NightscoutError.UNKNOWN)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: NightscoutException) {
            throw e
        } catch (e: UnknownHostException) {
            throw NightscoutException(NightscoutError.SITE_NOT_FOUND)
        } catch (e: ConnectException) {
            throw NightscoutException(NightscoutError.SITE_NOT_FOUND)
        } catch (e: IOException) {
            throw NightscoutException(NightscoutError.NETWORK_ERROR)
        }
    }

    /**
     * Reuses a visible "LoopFollow" subject, or creates a readable one. The token
     * of a new subject is derived locally from its `_id`, since the subject list
     * is served from a cache that lags behind writes.
     */
    suspend fun provisionReadOnlyToken(url: String, secret: String): String {
        val base = baseURL(url)
        val secretHash = sha1Hex(secret)
        fetchExisting(base, secretHash)?.let { return it }
        val id = createSubject(base, secretHash)
        return accessToken(SUBJECT_NAME, id, secretHash)
    }

    /** True when [secret] authenticates as the API secret; creates nothing. */
    suspend fun verifyAPISecret(url: String, secret: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return false
        val base = runCatching { baseURL(url) }.getOrNull() ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(subjectsURL(base)).get()
                    .header("api-secret", sha1Hex(trimmed)).header("Accept", "application/json").build()
                NightscoutUtils.client.newCall(request).execute().use { it.code == 200 }
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchExisting(base: HttpUrl, secretHash: String): String? = io {
        val request = Request.Builder().url(subjectsURL(base)).get()
            .header("api-secret", secretHash).header("Accept", "application/json").header("Cache-Control", "no-cache").build()
        NightscoutUtils.client.newCall(request).execute().use { response ->
            validate(response)
            val subjects = Prefs.json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonArray
                ?: throw NightscoutException(NightscoutError.UNKNOWN)
            subjects.mapNotNull { it as? JsonObject }.firstOrNull { it.string("name") == SUBJECT_NAME }?.string("accessToken")
        }
    }

    private suspend fun createSubject(base: HttpUrl, secretHash: String): String = io {
        val body = buildJsonObject {
            put("name", SUBJECT_NAME)
            put("roles", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("readable")) })
        }
        val request = Request.Builder().url(subjectsURL(base))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("api-secret", secretHash).build()
        NightscoutUtils.client.newCall(request).execute().use { response ->
            validate(response)
            // Current Nightscout wraps the created subject in an array; older versions return the object.
            val subject = when (val parsed = Prefs.json.parseToJsonElement(response.body?.string().orEmpty())) {
                is JsonArray -> parsed.firstOrNull() as? JsonObject
                is JsonObject -> parsed
                else -> null
            } ?: throw NightscoutException(NightscoutError.UNKNOWN)
            subject.string("_id")?.takeIf { it.isNotEmpty() } ?: throw NightscoutException(NightscoutError.UNKNOWN)
        }
    }

    /** Nightscout's subject token: abbreviated name + first 16 hex of sha1(sha1Hex(secret) + id). */
    fun accessToken(name: String, id: String, secretHash: String): String {
        val abbrev = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(10)
        return abbrev + "-" + sha1Hex(secretHash + id).take(16)
    }
}
