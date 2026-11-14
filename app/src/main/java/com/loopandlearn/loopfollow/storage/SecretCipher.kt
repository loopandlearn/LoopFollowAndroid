package com.loopandlearn.loopfollow.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts stored secrets with an AES-256-GCM key held in the Android Keystore.
 * An encrypted value is [PREFIX] followed by base64(IV + ciphertext).
 *
 * ANDROID-DEVIATION: secrets (Nightscout token, Dexcom password, APNs keys, the
 * Trio shared secret and the Loop QR code URL) are encrypted at rest; iOS keeps
 * them in plain UserDefaults. The key has no user-authentication requirement, so
 * background downloads, alarms and remote commands can read the secrets while the
 * phone is locked; the app is not direct-boot aware, so the keystore is available
 * whenever it runs. The key never leaves the device, so an encrypted value copied
 * to another device cannot be read there.
 */
internal object SecretCipher {
    const val PREFIX = "enc:v1:"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "loopfollow_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    /** Throws when [stored] was not encrypted with this device's key. */
    fun decrypt(stored: String): String {
        val data = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        require(data.size > IV_LENGTH) { "truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH))
        return String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH), Charsets.UTF_8)
    }

    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
            cachedKey = it
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().also { cachedKey = it }
    }
}
