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
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.model.DeployItem
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.model.DeployResult

// JSON of a deployment, shared by client and server so both sides always agree.

private const val PATH = "path"
private const val HOST = "host"
private const val SOURCE = "source"
private const val TARGET = "target"

/** A destination: its path, and the host when it is on another machine. */
fun DeployDestination.toJson(): JsonObject =
    buildJsonObject {
        put(PATH, path)
        put(HOST, host?.let(::JsonPrimitive) ?: JsonNull)
    }

/** Reads a destination written by [DeployDestination.toJson]. */
fun JsonElement.deployDestination(): DeployDestination {
    val fields = jsonObject
    return DeployDestination(fields.string(PATH), fields.stringOrNull(HOST))
}

/** What a deployment copied. */
fun DeployResult.toJson(): JsonObject =
    buildJsonObject {
        put("files", files)
        put("bytes", bytes)
    }

/** Reads a result written by [DeployResult.toJson]. */
fun JsonElement.deployResult(): DeployResult {
    val fields = jsonObject
    return DeployResult(fields.int("files"), fields.long("bytes"))
}

/** Saved deployments. */
fun List<DeployProfile>.toJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put("name", it.name)
                put("folders", it.folders.map(DeployFolder::toJson).let(::JsonArray))
                put("destination", it.destination)
                put("auto", it.auto)
            }
        },
    )

private fun DeployFolder.toJson(): JsonObject =
    buildJsonObject {
        put(SOURCE, source)
        put(TARGET, target)
    }

/** Reads deployments written by [toJson]. */
fun JsonElement.deployProfiles(): List<DeployProfile> =
    jsonArray.map {
        val fields = it.jsonObject
        // Saved before the automatic mode existed: manual.
        val auto = fields["auto"]?.let { _ -> fields.bool("auto") } ?: false
        DeployProfile(fields.string("name"), fields.folders(), fields.string("destination"), auto)
    }

// Saved before folders had a target: a list of paths, each landing at its own path.
private fun JsonObject.folders(): List<DeployFolder> {
    val saved = this["folders"]
    if (saved != null) {
        return saved.jsonArray.map {
            DeployFolder(
                it.jsonObject.string(SOURCE),
                it.jsonObject.string(TARGET),
            )
        }
    }
    return read("sources") { sources -> sources.strings() }.map { DeployFolder(it, it) }
}

/** What to deploy: the files of the project and where each lands. */
fun List<DeployItem>.itemsJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(SOURCE, it.source.toJson())
                put(TARGET, it.target)
            }
        },
    )

/** Reads items written by [itemsJson]. */
fun JsonElement.deployItems(): List<DeployItem> =
    jsonArray.map {
        DeployItem(
            it.jsonObject.read(SOURCE) { source -> source.entry() },
            it.jsonObject.string(TARGET),
        )
    }
