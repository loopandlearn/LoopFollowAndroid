package com.loopandlearn.loopfollow.remote.loop

import android.net.Uri
import com.loopandlearn.loopfollow.storage.ObservableValue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 one-time codes (HMAC-SHA1, 30 s steps, 6 digits) from Loop's otpauth:// QR code URL. */
object TOTPGenerator {
    const val PERIOD_SECONDS = 30L

    fun generateTOTP(secret: String, nowMillis: Long = System.currentTimeMillis()): String {
        val counter = nowMillis / 1000 / PERIOD_SECONDS
        val counterData = ByteArray(8) { i -> ((counter shr (56 - i * 8)) and 0xFF).toByte() }
        val hmac = hmacSHA1(base32Decode(secret), counterData)
        val offset = hmac.last().toInt() and 0x0F
        val value = ((hmac[offset].toInt() and 0xFF) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        return String.format(java.util.Locale.US, "%06d", (value and 0x7FFFFFFF) % 1_000_000)
    }

    /** The current code for an otpauth:// URL with a `secret`, or the `otp` query value of any other URL. */
    fun extractOTPFromURL(urlString: String): String? {
        if (urlString.isEmpty()) return null
        val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return null
        if (!uri.isHierarchical) return null
        if (uri.scheme == "otpauth") {
            runCatching { uri.getQueryParameter("secret") }.getOrNull()?.let { return generateTOTP(it) }
        }
        return runCatching { uri.getQueryParameter("otp") }.getOrNull()
    }

    private fun base32Decode(string: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (char in string.uppercase()) {
            val value = alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            while (bitsLeft >= 8) {
                bitsLeft -= 8
                result.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
            buffer = buffer and ((1 shl bitsLeft) - 1)
        }
        return result.toByteArray()
    }

    private fun hmacSHA1(key: ByteArray, data: ByteArray): ByteArray {
        // HMAC zero-pads the key, so an empty key equals a single zero byte; SecretKeySpec rejects empty keys.
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA1"))
        return mac.doFinal(data)
    }
}

/** Blocks reusing a one-time code for a second command within the same period. */
object TOTPService {
    val lastSentTOTP = ObservableValue<String?>(null)

    fun isTOTPBlocked(qrCodeURL: String): Boolean {
        val current = TOTPGenerator.extractOTPFromURL(qrCodeURL) ?: return false
        return current == lastSentTOTP.value
    }

    fun markTOTPAsUsed(qrCodeURL: String) {
        TOTPGenerator.extractOTPFromURL(qrCodeURL)?.let { lastSentTOTP.value = it }
    }

    fun resetTOTPUsage() {
        lastSentTOTP.value = null
    }
}
