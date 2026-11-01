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
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus

// JSON of the history of the Branches tool window: commits, and what a commit changed.

private const val HASH = "hash"

/** Commits of a history page. */
fun List<GitCommit>.commitsJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(HASH, it.hash)
                put("parents", it.parents.toJson())
                put("author", it.author)
                put("time", it.time)
                put("subject", it.subject)
                put("refs", it.refs.toJson())
            }
        },
    )

/** Reads commits of a history page. */
fun JsonElement.commits(): List<GitCommit> =
    jsonArray.map {
        val commit = it.jsonObject
        GitCommit(
            hash = commit.string(HASH),
            parents = commit.getValue("parents").strings(),
            author = commit.string("author"),
            time = commit.long("time"),
            subject = commit.string("subject"),
            refs = commit.getValue("refs").strings(),
        )
    }

/** Message and changed files of a commit. */
fun GitCommitDetails.toJson(): JsonObject {
    val fileArray =
        JsonArray(
            files.map { file ->
                buildJsonObject {
                    put("path", file.path)
                    put("status", file.status.name)
                    put("oldPath", file.oldPath?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
    return buildJsonObject {
        put(HASH, hash)
        put("message", message)
        put("files", fileArray)
    }
}

/** Reads the message and changed files of a commit. */
fun JsonElement.commitDetails(): GitCommitDetails {
    val details = jsonObject
    val files =
        details.getValue("files").jsonArray.map {
            val file = it.jsonObject
            GitCommitFile(
                path = file.string("path"),
                status = GitFileStatus.valueOf(file.string("status")),
                oldPath = file.stringOrNull("oldPath"),
            )
        }
    return GitCommitDetails(hash = details.string(HASH), message = details.string("message"), files = files)
}
