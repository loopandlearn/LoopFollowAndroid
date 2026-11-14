package com.loopandlearn.loopfollow.nightscout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Accessors with the lenient semantics of reading Foundation JSON objects:
// numbers read as doubles, integral numbers as ints, strings only as strings.

fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    if (p.booleanOrNull != null) return null
    return p.doubleOrNull
}

fun JsonObject.int(key: String): Int? {
    val d = double(key) ?: return null
    return if (d % 1.0 == 0.0) d.toInt() else null
}

fun JsonObject.string(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.booleanOrNull
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** True when the key holds a value other than JSON null. */
fun JsonObject.hasNonNull(key: String): Boolean {
    val v = this[key] ?: return false
    return v !is JsonNull
}

fun JsonArray.doubles(): List<Double>? {
    val result = ArrayList<Double>(size)
    for (e in this) {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        result.add(p.doubleOrNull ?: return null)
    }
    return result
}

fun JsonArray.strings(): List<String>? {
    val result = ArrayList<String>(size)
    for (e in this) {
        val p = e as? JsonPrimitive ?: return null
        if (!p.isString) return null
        result.add(p.content)
    }
    return result
}

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
