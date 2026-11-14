package com.loopandlearn.loopfollow.settings.importexport

import com.loopandlearn.loopfollow.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/**
 * JSON helpers for the settings QR code. The format is the plain JSON that
 * LoopFollow for iOS writes with Foundation's JSONEncoder: dates are seconds
 * since 2001-01-01 UTC, absent optionals are left out, and decoding fails when
 * a required key is missing or has the wrong type.
 */
internal object SettingsWire {
    /** Seconds between 1970-01-01 and Foundation's reference date 2001-01-01. */
    const val REFERENCE_DATE_OFFSET = 978_307_200.0

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun appVersion(): String = BuildConfig.VERSION_NAME

    fun epochToReference(epochSeconds: Double): Double = epochSeconds - REFERENCE_DATE_OFFSET

    fun referenceToEpoch(referenceSeconds: Double): Double = referenceSeconds + REFERENCE_DATE_OFFSET

    /** A date in plain decimal notation, which keeps the QR code short. */
    fun date(referenceSeconds: Double): JsonPrimitive = JsonPrimitive(BigDecimal.valueOf(referenceSeconds))

    fun parseObject(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
}

internal class SettingsDecodingException(message: String) : Exception(message)

private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

private fun JsonObject.primitive(key: String): JsonPrimitive {
    val element = present(key) ?: throw SettingsDecodingException("Missing key: $key")
    return element as? JsonPrimitive ?: throw SettingsDecodingException("Type mismatch: $key")
}

internal fun JsonObject.requireString(key: String): String {
    val value = primitive(key)
    if (!value.isString) throw SettingsDecodingException("Type mismatch: $key")
    return value.content
}

internal fun JsonObject.optionalString(key: String): String? = if (present(key) == null) null else requireString(key)

internal fun JsonObject.requireDouble(key: String): Double {
    val value = primitive(key)
    if (value.isString) throw SettingsDecodingException("Type mismatch: $key")
    return value.doubleOrNull ?: throw SettingsDecodingException("Type mismatch: $key")
}

internal fun JsonObject.optionalDouble(key: String): Double? = if (present(key) == null) null else requireDouble(key)

internal fun JsonObject.requireInt(key: String): Int {
    val value = primitive(key)
    if (value.isString) throw SettingsDecodingException("Type mismatch: $key")
    value.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) return it.toInt() }
    val double = value.doubleOrNull
    if (double != null && double == Math.floor(double) && double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) return double.toInt()
    throw SettingsDecodingException("Type mismatch: $key")
}

internal fun JsonObject.optionalInt(key: String): Int? = if (present(key) == null) null else requireInt(key)

internal fun JsonObject.requireBool(key: String): Boolean {
    val value = primitive(key)
    if (value.isString) throw SettingsDecodingException("Type mismatch: $key")
    return value.booleanOrNull ?: throw SettingsDecodingException("Type mismatch: $key")
}

internal fun JsonObject.requireObject(key: String): JsonObject {
    val element = present(key) ?: throw SettingsDecodingException("Missing key: $key")
    return element as? JsonObject ?: throw SettingsDecodingException("Type mismatch: $key")
}

internal fun JsonObject.optionalObject(key: String): JsonObject? = if (present(key) == null) null else requireObject(key)
