package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.RunChunk

private const val LINES = "lines"
private const val FINISHED = "finished"
private const val EXIT_CODE = "exitCode"
private const val ERROR = "error"

/** One chunk of a run; the fields at their default value are left out. */
fun RunChunk.toJson(): JsonObject =
    buildJsonObject {
        put("seq", seq)
        if (lines.isNotEmpty()) put(LINES, JsonArray(lines.map(::JsonPrimitive)))
        if (finished) put(FINISHED, true)
        if (exitCode != null) put(EXIT_CODE, exitCode)
        if (error != null) put(ERROR, error)
    }

/** Reads a chunk written by [RunChunk.toJson]. */
fun JsonElement.runChunk(): RunChunk {
    val chunk = jsonObject
    val lines = chunk[LINES]?.jsonArray?.map { it.string() }.orEmpty()
    return RunChunk(
        seq = chunk.long("seq"),
        lines = lines,
        finished = chunk[FINISHED]?.let { chunk.bool(FINISHED) } ?: false,
        exitCode = chunk[EXIT_CODE]?.let { chunk.int(EXIT_CODE) },
        error = chunk.stringOrNull(ERROR),
    )
}
