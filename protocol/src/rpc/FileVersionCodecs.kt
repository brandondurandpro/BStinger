package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileVersion

// JSON of a file version: its stat, its key on the disk, and the hash of its bytes, next to the content read.

private const val VERSION = "version"
private const val MTIME = "mtime"
private const val SIZE = "size"
private const val FILE_KEY = "fileKey"
private const val SHA256 = "sha256"

/** A version of a file: what a save checks, and what the watch reports. */
fun FileVersion.toJson(): JsonObject {
    val version = this
    val key = version.fileKey
    val keyJson = if (key == null) JsonNull else JsonPrimitive(key)
    return buildJsonObject {
        put(MTIME, version.mtimeMs)
        put(SIZE, version.size)
        put(FILE_KEY, keyJson)
        put(SHA256, version.sha256)
    }
}

/** An optional version, JSON null when absent. */
fun FileVersion?.toJsonOrNull(): JsonElement = this?.toJson() ?: JsonNull

/** Reads a version written by [FileVersion.toJson], `null` when absent (nothing was known). */
fun JsonElement.versionOrNull(): FileVersion? {
    if (this == JsonNull) return null
    val fields = jsonObject
    return FileVersion(
        mtimeMs = fields.long(MTIME),
        size = fields.long(SIZE),
        fileKey = fields.get(FILE_KEY)?.stringOrNull(),
        sha256 = fields.string(SHA256),
    )
}

/** Reads a version that must be present, failing the request with the error of a missing field when it is not. */
fun JsonElement.fileVersion(): FileVersion = versionOrNull() ?: throw InvalidParamsException("Missing field '$VERSION'")
