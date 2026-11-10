package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.MergeHistory
import org.example.kotlineditor.model.MergeSides

// JSON of the three-way merge of a conflicted file.

private const val BASE = "base"
private const val OURS = "ours"
private const val THEIRS = "theirs"

/** The three versions of a conflicted file; a missing version is `null`. */
fun MergeSides.toJson(): JsonObject =
    buildJsonObject {
        put(BASE, base?.toJson() ?: JsonNull)
        put(OURS, ours?.toJson() ?: JsonNull)
        put(THEIRS, theirs?.toJson() ?: JsonNull)
    }

/** Reads the three versions of a conflicted file. */
fun JsonElement.mergeSides(): MergeSides {
    val sides = jsonObject
    return MergeSides(sides.textOrNull(BASE), sides.textOrNull(OURS), sides.textOrNull(THEIRS))
}

private fun JsonObject.textOrNull(name: String): FileContent.Text? {
    val side = get(name)
    return if (side == null || side == JsonNull) null else side.content() as? FileContent.Text
}

/** Reads a file content that must be text (the resolution of a conflict). */
fun JsonElement.textContent(): FileContent.Text =
    content() as? FileContent.Text ?: throw InvalidParamsException("Not a text file content")

/** The commits of a conflicted file on each side of a merge. */
fun MergeHistory.toJson(): JsonObject =
    buildJsonObject {
        put(OURS, ours.commitsJson())
        put(THEIRS, theirs.commitsJson())
    }

/** Reads the commits of a conflicted file on each side of a merge. */
fun JsonElement.mergeHistory(): MergeHistory =
    MergeHistory(jsonObject.field(OURS).commits(), jsonObject.field(THEIRS).commits())
