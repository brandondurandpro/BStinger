package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile

private const val PATH = "path"
private const val NEW_PATH = "newPath"
private const val DIRECTORY = "directory"

/** The files of a file operation. */
fun List<OperatedFile>.toJson(): JsonArray =
    JsonArray(
        map { file ->
            buildJsonObject {
                put(PATH, file.path)
                file.newPath?.let { put(NEW_PATH, it) }
                put(DIRECTORY, file.directory)
            }
        },
    )

/** Reads the files of a file operation. */
fun JsonElement.operatedFiles(): List<OperatedFile> = jsonArray.map { it.jsonObject.operatedFile() }

private fun JsonObject.operatedFile(): OperatedFile =
    OperatedFile(string(PATH), stringOrNull(NEW_PATH), get(DIRECTORY)?.let { bool(DIRECTORY) } ?: false)

/** Reads a file operation by its name, null when unknown. */
fun fileOperationNamed(name: String): FileOperation? = FileOperation.entries.firstOrNull { it.name == name }
