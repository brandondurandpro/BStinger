package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.Locale

/**
 * Whether the `fileOperations.<name>` capability (`willRename`, `didCreate`...) of a server's [workspace]
 * capabilities has a filter matching [path] (a file, or a directory when [directory]): its scheme, its glob pattern
 * (every PHP file under any directory, say), its kind (`file`, `folder`) and its case option.
 */
fun fileOperationMatches(workspace: JsonElement?, name: String, path: String, directory: Boolean): Boolean {
    val operations = (workspace as? JsonObject)?.obj("fileOperations")
    val filters = operations?.obj(name)?.array("filters").orEmpty()
    return filters.any { filterMatches(it as? JsonObject, path, directory) }
}

private fun filterMatches(filter: JsonObject?, path: String, directory: Boolean): Boolean {
    val scheme = filter?.string("scheme") ?: "file"
    val pattern = filter?.obj("pattern")
    val glob = pattern?.string("glob")
    val kind = pattern?.string("matches")
    val isFolderFilter = kind == "folder"
    val kindMatches = kind == null || isFolderFilter == directory
    val applies = scheme == "file" && kindMatches
    if (glob == null || !applies) return false
    val ignoreCase = (pattern.obj("options")?.get("ignoreCase") as? JsonPrimitive)?.booleanOrNull == true
    return globMatches(glob, path, ignoreCase)
}

// LSP globs are those of java.nio (`*`, `**`, `?`, `{a,b}`, `[a-z]`), on paths with `/`.
private fun globMatches(glob: String, path: String, ignoreCase: Boolean): Boolean {
    val normalized = path.replace('\\', '/')
    val text = if (ignoreCase) normalized.lowercase(Locale.ROOT) else normalized
    val pattern = if (ignoreCase) glob.lowercase(Locale.ROOT) else glob
    val matcher = runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
    return matcher?.matches(Paths.get(text)) == true
}

/**
 * The params of `workspace/will<Operation>Files` and `workspace/did<Operation>Files`: `{oldUri, newUri}` for each
 * renamed file, `{uri}` for each created or deleted one.
 */
fun fileOperationParams(operation: FileOperation, files: List<OperatedFile>): JsonObject =
    buildJsonObject {
        putJsonArray("files") {
            files.forEach { file ->
                addJsonObject {
                    val newPath = file.newPath
                    if (operation == FileOperation.Rename && newPath != null) {
                        put("oldUri", uriOf(file.path))
                        put("newUri", uriOf(newPath))
                    } else {
                        put("uri", uriOf(file.path))
                    }
                }
            }
        }
    }

/** The [files] the `fileOperations.<name>` capability of [workspace] asks about (by the old path of a rename). */
fun filesMatching(workspace: JsonElement?, name: String, files: List<OperatedFile>): List<OperatedFile> =
    files.filter { fileOperationMatches(workspace, name, it.path, it.directory) }

private fun uriOf(path: String): String = Paths.get(path).toUri().toString()
