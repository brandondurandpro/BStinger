package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitFileStatus

// JSON of Git data. Enums travel by name.

/** A Git status map: `{"path": "Modified", ...}`. */
fun Map<String, GitFileStatus>.toJson(): JsonObject = JsonObject(mapValues { JsonPrimitive(it.value.name) })

/** Reads a Git status map. */
fun JsonElement.gitStatus(): Map<String, GitFileStatus> =
    jsonObject.mapValues { GitFileStatus.valueOf(it.value.string()) }

/** The lines of a diff. */
fun List<DiffLine>.diffJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put("kind", it.kind.name)
                put("oldLine", it.oldLine)
                put("newLine", it.newLine)
                put("text", it.text)
            }
        },
    )

/** Reads the lines of a diff. */
fun JsonElement.diffLines(): List<DiffLine> =
    jsonArray.map {
        val line = it.jsonObject
        DiffLine(DiffKind.valueOf(line.string("kind")), line.int("oldLine"), line.int("newLine"), line.string("text"))
    }

/** Blame annotations. */
fun List<BlameLine>.blameJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put("line", it.line)
                put("commit", it.commit)
                put("author", it.author)
                put("date", it.date)
            }
        },
    )

/** Reads blame annotations. */
fun JsonElement.blameLines(): List<BlameLine> =
    jsonArray.map {
        val line = it.jsonObject
        BlameLine(line.int("line"), line.string("commit"), line.string("author"), line.string("date"))
    }
