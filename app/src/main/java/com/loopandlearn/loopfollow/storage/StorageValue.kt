package com.loopandlearn.loopfollow.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object Prefs {
    lateinit var prefs: SharedPreferences
        private set

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences("loopfollow", Context.MODE_PRIVATE)
        }
    }
}

/**
 * A persisted, observable value. Every value is stored as its JSON encoding
 * under [key], so any serializable type works the same way.
 *
 * A [secret] value is stored encrypted with [SecretCipher]. A plaintext value
 * found under a secret key is read as is and saved back encrypted. A value that
 * cannot be decrypted (the keystore key is gone) reads as the default.
 */
class StorageValue<T>(
    val key: String,
    val defaultValue: T,
    private val serializer: KSerializer<T>,
    private val secret: Boolean = false,
) {
    private var undecryptableLogged = false
    private val state = MutableStateFlow(load())

    val flow: StateFlow<T> = state.asStateFlow()

    var value: T
        get() = state.value
        set(newValue) {
            if (newValue == state.value && exists) return
            state.value = newValue
            persist(newValue)
        }

    val exists: Boolean
        get() = Prefs.prefs.contains(key)

    fun remove() {
        Prefs.prefs.edit().remove(key).apply()
        state.value = defaultValue
    }

    /** Re-reads the persisted value, used after an import replaces stored settings. */
    fun reload() {
        state.value = load()
    }

    private fun persist(newValue: T) {
        val encoded = Prefs.json.encodeToString(serializer, newValue)
        val stored = if (secret) {
            try {
                SecretCipher.encrypt(encoded)
            } catch (e: Exception) {
                Log.w("LoopFollow", "Could not encrypt \"$key\" (${e.javaClass.simpleName}); it is kept in memory only")
                return
            }
        } else {
            encoded
        }
        Prefs.prefs.edit().putString(key, stored).apply()
    }

    private fun load(): T {
        val stored = Prefs.prefs.getString(key, null) ?: return defaultValue
        val raw = if (secret && SecretCipher.isEncrypted(stored)) {
            try {
                SecretCipher.decrypt(stored)
            } catch (e: Exception) {
                if (!undecryptableLogged) {
                    undecryptableLogged = true
                    Log.w("LoopFollow", "Stored secret \"$key\" cannot be decrypted (${e.javaClass.simpleName}); using the default")
                }
                return defaultValue
            }
        } else {
            stored
        }
        val decoded = try {
            Prefs.json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            // Only the key and the error type are logged; the stored text can hold a secret.
            Log.w("LoopFollow", "Stored value for \"$key\" is unreadable (${e.javaClass.simpleName}); using the default")
            return defaultValue
        }
        if (secret && !SecretCipher.isEncrypted(stored)) persist(decoded)
        return decoded
    }
}

inline fun <reified T> storageValue(key: String, defaultValue: T, secret: Boolean = false): StorageValue<T> =
    StorageValue(key, defaultValue, serializer(), secret)

/** In-memory observable value. */
class ObservableValue<T>(default: T) {
    private val state = MutableStateFlow(default)
    val flow: StateFlow<T> = state.asStateFlow()

    var value: T
        get() = state.value
        set(newValue) {
            state.value = newValue
        }
}
