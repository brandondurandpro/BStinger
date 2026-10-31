package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.pathName

// JSON of files and their content, shared by client and server so both sides always agree.

private const val TEXT = "text"
private const val BINARY = "binary"
private const val TOO_LARGE = "tooLarge"
private const val ERROR = "error"

/** An entry: only the path and the kind travel, the name is derived from the path. */
fun FileEntry.toJson(): JsonObject =
    buildJsonObject {
        put("path", path)
        put("isDirectory", isDirectory)
    }

/** An optional entry, JSON null when absent. */
fun FileEntry?.toJsonOrNull(): JsonElement = this?.toJson() ?: JsonNull

/** Reads an entry written by [FileEntry.toJson]. */
fun JsonElement.entry(): FileEntry {
    val fields = jsonObject
    val path = fields.string("path")
    return FileEntry(path, pathName(path), fields.bool("isDirectory"))
}

/** Reads an optional entry. */
fun JsonElement.entryOrNull(): FileEntry? = if (this == JsonNull) null else entry()

/** A list of entries. */
fun List<FileEntry>.toJson(): JsonElement = JsonArray(map { it.toJson() })

/** Reads a list of entries. */
fun JsonElement.entries(): List<FileEntry> = jsonArray.map { it.entry() }

/** A file content; text is sent as lines, already split by the server. */
fun FileContent.toJson(): JsonObject =
    buildJsonObject {
        when (val content = this@toJson) {
            is FileContent.Text -> {
                put("type", TEXT)
                put("lineSeparator", content.lineSeparator)
                put("lines", content.lines.toJson())
            }

            FileContent.Binary -> {
                put("type", BINARY)
            }

            is FileContent.TooLarge -> {
                put("type", TOO_LARGE)
                put("size", content.size)
            }

            is FileContent.Error -> {
                put("type", ERROR)
                put("message", content.message)
            }
        }
    }

/** Reads a file content. */
fun JsonElement.content(): FileContent {
    val fields = jsonObject
    return when (fields.string("type")) {
        TEXT -> FileContent.Text(fields.field("lines").strings(), fields.string("lineSeparator"))
        BINARY -> FileContent.Binary
        TOO_LARGE -> FileContent.TooLarge(fields.long("size"))
        else -> FileContent.Error(fields.string("message"))
    }
}

/** A list of strings. */
fun List<String>.toJson(): JsonArray = JsonArray(map(::JsonPrimitive))

/** Reads a list of strings. */
fun JsonElement.strings(): List<String> = jsonArray.map { it.string() }
