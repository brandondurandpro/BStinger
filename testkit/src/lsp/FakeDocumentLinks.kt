package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

// `require 'x.php'`, `include_once __DIR__ . '/x.php'`: the path in quotes, group 3.
private val REQUIRE = Regex("""\b(require|include)(_once)?\s*\(?\s*(?:__DIR__\s*\.\s*)?(['"])([^'"]+)\3""")
private const val PATH_GROUP = 4
private val URL = Regex("""https?://[^\s'")>]+""")

/**
 * The document links of the [FakeLanguageServer], like intelephense: the files of `require` and `include`, whose
 * target only `documentLink/resolve` gives, and the web addresses written in the code, with their target at once.
 */
internal class FakeDocumentLinks(private val code: FakePhpCode) {
    fun links(params: JsonElement?): JsonElement {
        val textDocument = (params as? JsonObject)?.get("textDocument") as? JsonObject
        val uri = (textDocument?.get("uri") as? JsonPrimitive)?.contentOrNull
        val lines = uri?.let { code.files[it] } ?: return JsonNull
        return buildJsonArray {
            lines.forEachIndexed { index, text ->
                REQUIRE.findAll(text).forEach { match ->
                    val group = match.groups[PATH_GROUP] ?: return@forEach
                    val target = URI(uri).resolve(group.value.removePrefix("/")).toString()
                    add(link(index, group.range.first, group.range.last + 1, data = target))
                }
                URL.findAll(text).forEach { match ->
                    add(link(index, match.range.first, match.range.last + 1, target = match.value))
                }
            }
        }
    }

    // The target of a required file, kept in the link's data.
    fun resolve(params: JsonElement?): JsonElement {
        val link = params as? JsonObject ?: return JsonNull
        val target = link["data"] ?: return link
        return JsonObject(link + mapOf("target" to target, "tooltip" to JsonPrimitive("Open the required file")))
    }

    private fun link(line: Int, start: Int, end: Int, target: String? = null, data: String? = null): JsonObject =
        buildJsonObject {
            putJsonObject("range") {
                putJsonObject("start") {
                    put("line", line)
                    put("character", start)
                }
                putJsonObject("end") {
                    put("line", line)
                    put("character", end)
                }
            }
            target?.let { put("target", it) }
            data?.let { put("data", it) }
        }
}
