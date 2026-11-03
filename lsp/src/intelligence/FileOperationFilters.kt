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
import java.nio.file.Paths

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

// LSP globs (`*`, `**`, `?`, `{a,b}`, `[a-z]`, `[!a]`) on paths with `/`, matched here rather than by java.nio, whose
// matcher ignores case on Windows whatever the filter says.
private fun globMatches(glob: String, path: String, ignoreCase: Boolean): Boolean {
    val normalized = path.replace('\\', '/')
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val regex = runCatching { Regex(globRegex(glob), options) }.getOrNull()
    return regex?.matches(normalized) == true
}

/** The regular expression of an LSP [glob]. */
internal fun globRegex(glob: String): String {
    val out = StringBuilder()
    var index = 0
    var braces = 0
    while (index < glob.length) {
        when (glob[index]) {
            '*' -> index = appendStars(glob, index, out)
            '[' -> index = appendClass(glob, index, out)
            else -> braces += appendOther(glob[index], braces, out)
        }
        index++
    }
    return out.toString()
}

// Appends `*`, `**` or `**/` starting at [start]; returns the index of its last character.
private fun appendStars(glob: String, start: Int, out: StringBuilder): Int =
    when {
        glob.startsWith("**/", start) -> out.append("(?:.*/)?").let { start + 2 }
        glob.startsWith("**", start) -> out.append(".*").let { start + 1 }
        else -> out.append("[^/]*").let { start }
    }

// Appends [char] inside [braces] levels of `{a,b}`; returns how it changes the level.
private fun appendOther(char: Char, braces: Int, out: StringBuilder): Int {
    val inBraces = braces > 0
    val (text, delta) =
        when {
            char == '?' -> "[^/]" to 0
            char == '{' -> "(?:" to 1
            char == '}' && inBraces -> ")" to -1
            char == ',' && inBraces -> "|" to 0
            else -> Regex.escape(char.toString()) to 0
        }
    out.append(text)
    return delta
}

// Appends the character class starting at [start] (`[a-z]`, `[!a]`); returns the index of its `]`.
private fun appendClass(glob: String, start: Int, out: StringBuilder): Int {
    val end = glob.indexOf(']', start + 1)
    require(end > start) { "Unclosed [ in $glob" }
    val body = glob.substring(start + 1, end)
    val negated = body.startsWith("!")
    val chars = if (negated) body.drop(1) else body
    val escaped = chars.replace("\\", "\\\\").replace("[", "\\[")
    out.append(if (negated) "[^$escaped]" else "[$escaped]")
    return end
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
