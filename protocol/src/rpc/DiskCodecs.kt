package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.DiskChanges
import org.example.kotlineditor.model.DiskCursor
import org.example.kotlineditor.model.FileVersion

// JSON of the changes on disk (`fs/changes`), shared by client and server.

/** A position in the changes of a project. */
fun DiskCursor.toJson(): JsonObject =
    buildJsonObject {
        put("watch", watch)
        put("version", version)
    }

/** Reads a cursor written by [DiskCursor.toJson]. */
fun JsonElement.diskCursor(): DiskCursor {
    val fields = jsonObject
    return DiskCursor(fields.long("watch"), fields.long("version"))
}

/** Changes on disk. */
fun DiskChanges.toJson(): JsonObject =
    buildJsonObject {
        put("cursor", cursor.toJson())
        put("paths", paths.toJson())
        put("complete", complete)
        if (versions.isNotEmpty()) put("versions", versions.toJson())
    }

/** Reads changes written by [DiskChanges.toJson]. */
fun JsonElement.diskChanges(): DiskChanges {
    val fields = jsonObject
    val versions = fields.get("versions")?.versions() ?: emptyMap()
    return DiskChanges(
        fields.field("cursor").diskCursor(),
        fields.field("paths").strings(),
        fields.bool("complete"),
        versions,
    )
}

/** The versions of a changes answer, by path (an old answer carries none). */
fun JsonElement.versions(): Map<String, FileVersion> =
    jsonObject.entries.associate { (path, version) -> path to version.fileVersion() }

/** The versions of a changes answer, by path. */
fun Map<String, FileVersion>.toJson(): JsonElement =
    buildJsonObject {
        for ((path, version) in this@toJson) put(path, version.toJson())
    }
