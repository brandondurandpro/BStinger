package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val PHP = ".php"

/** The `fileOperations` capability of the [FakeLanguageServer]: every operation, on the PHP files. */
internal fun fakeFileOperationsCapability(): JsonObject =
    buildJsonObject {
        val filters =
            buildJsonObject {
                putJsonArray("filters") {
                    addJsonObject {
                        put("scheme", "file")
                        putJsonObject("pattern") {
                            put("glob", "**/*.php")
                            put("matches", "file")
                        }
                    }
                }
            }
        listOf("Create", "Rename", "Delete").forEach { operation ->
            put("will$operation", filters)
            put("did$operation", filters)
        }
    }

/**
 * `workspace/willRenameFiles` of the [FakeLanguageServer], like phpactor: the class named after a renamed file
 * (`Greeter.php` to `Welcomer.php`) is renamed with it, in every file.
 */
internal class FakeFileOperations(private val code: FakePhpCode, private val intelligence: FakeIntelligence) {
    fun willRename(params: JsonElement?): JsonElement {
        val files = ((params as? JsonObject)?.get("files") as? JsonArray).orEmpty()
        val renamed = files.mapNotNull { renamedClass(it as? JsonObject) }
        // One rename at a time is what the IDE asks; the first is enough here.
        val (oldUri, names) = renamed.firstOrNull() ?: return JsonNull
        val (oldName, newName) = names
        val declared = code.declarationsNamed(oldName).any { it.uri == oldUri }
        return if (declared) intelligence.renameEdit(oldName, oldUri, newName) else JsonNull
    }

    // The old URI of a renamed PHP file, with the old and new names of its class.
    private fun renamedClass(file: JsonObject?): Pair<String, Pair<String, String>>? {
        val oldUri = (file?.get("oldUri") as? JsonPrimitive)?.contentOrNull
        val newUri = (file?.get("newUri") as? JsonPrimitive)?.contentOrNull
        val oldName = oldUri?.let(::classNameOf)
        val newName = newUri?.let(::classNameOf)
        if (oldUri == null || oldName == null || newName == null) return null
        return oldUri to (oldName to newName)
    }

    private fun classNameOf(uri: String): String? = uri.substringAfterLast('/').takeIf { it.endsWith(PHP) }?.removeSuffix(PHP)
}
