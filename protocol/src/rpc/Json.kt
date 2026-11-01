package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Readers of JSON-RPC params and results; a missing or mistyped field throws InvalidParamsException, which the
// server answers with the JSON-RPC error INVALID_PARAMS.

/** Reads the field [key] with [read], turning a wrong type into an [InvalidParamsException]. */
inline fun <T> JsonObject.read(key: String, read: (JsonElement) -> T): T {
    val value = field(key)
    return try {
        read(value)
    } catch (e: InvalidParamsException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw InvalidParamsException("Invalid field '$key': ${e.message}", e)
    }
}

/** The string field [key]. */
fun JsonObject.string(key: String): String = read(key) { it.jsonPrimitive.content }

/** The string field [key], null when missing or null. */
fun JsonObject.stringOrNull(key: String): String? = get(key)?.stringOrNull()

/** The int field [key]. */
fun JsonObject.int(key: String): Int = read(key) { it.jsonPrimitive.int }

/** The long field [key]. */
fun JsonObject.long(key: String): Long = read(key) { it.jsonPrimitive.long }

/** The boolean field [key]. */
fun JsonObject.bool(key: String): Boolean = read(key) { it.jsonPrimitive.boolean }

/** The field [key], which must be present. */
fun JsonObject.field(key: String): JsonElement = get(key) ?: throw InvalidParamsException("Missing field '$key'")

/** This string, or null for JSON null. */
fun JsonElement.stringOrNull(): String? = if (this == JsonNull) null else jsonPrimitive.content

/** This string. */
fun JsonElement.string(): String = jsonPrimitive.content

/** The params of a request as an object (absent params are an empty object). */
fun JsonElement.params(): JsonObject =
    when (this) {
        JsonNull -> JsonObject(emptyMap())
        is JsonObject -> this
        else -> throw InvalidParamsException("Params must be an object (by-name)")
    }
