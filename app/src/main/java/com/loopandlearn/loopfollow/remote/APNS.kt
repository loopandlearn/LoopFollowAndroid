package com.loopandlearn.loopfollow.remote

import android.util.Base64
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun base64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.NO_WRAP).replace("+", "-").replace("/", "_").replace("=", "")

/** ES256 tokens for APNs, cached for 55 minutes per key and team. */
object JWTManager {
    private data class Cached(val jwt: String, val expiresAt: Long)

    private val cache = HashMap<String, Cached>()
    private const val TTL_MS = 55 * 60 * 1000L

    @Synchronized
    fun getOrGenerateJWT(keyId: String, teamId: String, apnsKey: String): String? {
        val cacheKey = "$keyId:$teamId"
        cache[cacheKey]?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { return it.jwt }
        return try {
            val key = loadPrivateKey(apnsKey)
            val header = base64Url(buildJsonObject { put("alg", "ES256"); put("kid", keyId) }.toString().toByteArray())
            val payload = base64Url(buildJsonObject { put("iss", teamId); put("iat", System.currentTimeMillis() / 1000) }.toString().toByteArray())
            val signingInput = "$header.$payload"
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(key)
            signer.update(signingInput.toByteArray())
            val jwt = "$signingInput.${base64Url(derToRaw(signer.sign()))}"
            cache[cacheKey] = Cached(jwt, System.currentTimeMillis() + TTL_MS)
            LogManager.log(LogManager.Category.APNS, "JWT generated (TTL 55 min)")
            jwt
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.APNS, "Failed to sign JWT: ${e.message}")
            null
        }
    }

    @Synchronized
    fun invalidateCache() {
        cache.clear()
        LogManager.log(LogManager.Category.APNS, "JWT cache invalidated")
    }

    private fun loadPrivateKey(apnsKey: String): ECPrivateKey {
        val cleaned = apnsKey.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        val der = Base64.decode(cleaned, Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
    }

    /** JWS wants the raw 64-byte r||s; the JCA signer returns DER. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 3
        val rLength = der[offset].toInt()
        val r = der.copyOfRange(offset + 1, offset + 1 + rLength)
        offset += 1 + rLength + 1
        val sLength = der[offset].toInt()
        val s = der.copyOfRange(offset + 1, offset + 1 + sLength)
        fun fixed(b: ByteArray): ByteArray {
            val trimmed = b.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return fixed(r) + fixed(s)
    }
}

/** AES-GCM with a key derived from the shared secret; output is base64(nonce + ciphertext + tag). */
class SecureMessenger(sharedSecret: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(sharedSecret.toByteArray()), "AES")

    fun encrypt(json: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + cipher.doFinal(json.toByteArray()), Base64.NO_WRAP)
    }
}

object APNSEnvironment {
    // ANDROID-DEVIATION: Debug builds can point pushes at a local test server; release builds always use Apple's servers.
    private val debugBaseURL = com.loopandlearn.loopfollow.storage.storageValue("debugApnsBaseURL", "")

    fun baseURL(production: Boolean): String {
        if (com.loopandlearn.loopfollow.BuildConfig.DEBUG && debugBaseURL.value.isNotEmpty()) return debugBaseURL.value
        return if (production) "https://api.push.apple.com" else "https://api.sandbox.push.apple.com"
    }
}

/** Sends one push through APNs; returns null on success, else the error message. */
object APNSDelivery {
    /** The largest payload APNs accepts. */
    const val MAX_PAYLOAD_BYTES = 4096

    fun tooLargeMessage(size: Int) = "The command is too large to send remotely ($size of $MAX_PAYLOAD_BYTES bytes)."

    suspend fun send(
        body: String,
        deviceToken: String,
        topic: String,
        production: Boolean,
        keyId: String,
        teamId: String,
        apnsKey: String,
        expiration: String? = null,
        collapseID: String? = null,
    ): String? {
        val size = body.toByteArray(Charsets.UTF_8).size
        if (size > MAX_PAYLOAD_BYTES) return tooLargeMessage(size).also { LogManager.log(LogManager.Category.APNS, it) }
        val jwt = JWTManager.getOrGenerateJWT(keyId, teamId, apnsKey)
            ?: return "Failed to generate JWT, please check that the APNS Key ID, APNS Key and Team ID are correct.".also {
                LogManager.log(LogManager.Category.APNS, it)
            }
        val url = "${APNSEnvironment.baseURL(production)}/3/device/$deviceToken"
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("authorization", "bearer $jwt")
                    .header("apns-priority", "10")
                    .header("apns-topic", topic)
                    .header("apns-push-type", "alert")
                    .apply {
                        expiration?.let { header("apns-expiration", it) }
                        collapseID?.let { header("apns-collapse-id", it) }
                    }
                    .build()
                NightscoutUtils.client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val reason = runCatching { ((Prefs.json.parseToJsonElement(text) as? JsonObject)?.get("reason") as? JsonPrimitive)?.content }.getOrNull() ?: ""
                    if (response.code == 200) {
                        LogManager.log(LogManager.Category.APNS, "APNS notification sent successfully")
                        null
                    } else {
                        if (response.code == 403) JWTManager.invalidateCache()
                        LogManager.log(LogManager.Category.APNS, "APNS error ${response.code}: $reason")
                        "${message(response.code)} $reason"
                    }
                }
            } catch (e: Exception) {
                "Failed to send push notification: ${e.message}".also { LogManager.log(LogManager.Category.APNS, it) }
            }
        }
    }

    fun message(status: Int): String = when (status) {
        400 -> "Bad request. The request was invalid or malformed."
        403 -> "Authentication error. Check your certificate or authentication token."
        404 -> "Invalid request: The :path value was incorrect."
        405 -> "Invalid request: Only POST requests are supported."
        410 -> "The device token is no longer active for the topic."
        413 -> "Payload too large. The notification payload exceeded the size limit."
        429 -> "Too many requests."
        500 -> "Internal server error at APNs."
        503 -> "Service unavailable. The server is temporarily unavailable. Try again later."
        else -> "Unexpected status code: $status."
    }
}
