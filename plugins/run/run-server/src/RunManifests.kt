package org.example.kotlineditor.run.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.RunManifest
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.server.LocalServices

private val MANIFEST_NAMES = setOf("package.json", "composer.json")

// A manifest of a dependency is not the project's own.
private val DEPENDENCY_DIRECTORIES = setOf("node_modules", "vendor")

/**
 * The `package.json` and `composer.json` of the project at [root] with their text and their sibling files, read
 * where the files are: a remote project answers in one request instead of the client walking and reading it.
 */
internal suspend fun findRunManifests(services: LocalServices, root: String): List<RunManifest> {
    val rootEntry = FileEntry(root, pathName(root), true)
    val files = services.search.walkFiles(rootEntry)
    val relatives = files.map { it to relativeToRoot(it.path, root) }
    val manifests = relatives.filter { (_, relative) -> isProjectManifest(relative) }
    val allRelatives = relatives.map { it.second }
    return manifests.mapNotNull { (file, relative) ->
        readManifest(services, file, relative, allRelatives)
    }
}

private fun isProjectManifest(relative: String): Boolean {
    val parts = relative.split('/')
    val vendored = parts.dropLast(1).any { it in DEPENDENCY_DIRECTORIES }
    return parts.lastOrNull() in MANIFEST_NAMES && !vendored
}

private suspend fun readManifest(
    services: LocalServices,
    file: FileEntry,
    relative: String,
    allRelatives: List<String>,
): RunManifest? {
    val content = runCatching { services.fileSystem.read(file) }.getOrNull()
    val text = content as? FileContent.Text ?: return null
    val directory = relative.substringBeforeLast('/', "")
    val prefix = if (directory.isEmpty()) "" else "$directory/"
    val siblings =
        allRelatives
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { it.removePrefix(prefix) }
            .toSet()
    return RunManifest(relative.substringAfterLast('/'), directory, text.lines.joinToString("\n"), siblings)
}

internal fun List<RunManifest>.manifestsJson(): JsonElement =
    JsonArray(
        map { manifest ->
            buildJsonObject {
                put("fileName", manifest.fileName)
                put("directory", manifest.directory)
                put("content", manifest.content)
                put("siblings", manifest.siblings.toList().toJson())
            }
        },
    )
