package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Formatting while typing of the [FakeLanguageServer]: after `;`, the spaces before it go (`$a = 1 ;` becomes
 * `$a = 1;`); after a `}` alone on its line, the line takes the indentation of the line of its `{`.
 */
internal class FakeTypeFormatting(private val code: FakePhpCode) {
    fun format(params: JsonElement?): JsonElement {
        val fields = params as? JsonObject
        val uri = ((fields?.get("textDocument") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        val position = fields?.get("position") as? JsonObject
        val line = (position?.get("line") as? JsonPrimitive)?.intOrNull
        val character = (position?.get("character") as? JsonPrimitive)?.intOrNull
        val typed = (fields?.get("ch") as? JsonPrimitive)?.contentOrNull
        val lines = uri?.let { code.files[it] }
        val text = line?.let { lines?.getOrNull(it) }
        if (lines == null || line == null || text == null || character == null) return JsonArray(emptyList())
        val edit =
            when (typed) {
                ";" -> spacesBefore(line, text, character - 1)
                "}" -> braceIndent(lines, line, text)
                else -> null
            }
        return buildJsonArray { edit?.let { add(it) } }
    }

    private fun spacesBefore(line: Int, text: String, semicolon: Int): JsonObject? {
        if (text.getOrNull(semicolon) != ';') return null
        var start = semicolon
        while (start > 0 && text[start - 1] == ' ') start--
        return if (start < semicolon) edit(line, start, semicolon, "") else null
    }

    private fun braceIndent(lines: List<String>, line: Int, text: String): JsonObject? {
        if (text.trim() != "}") return null
        var depth = 0
        var opening = line - 1
        while (opening >= 0) {
            val current = lines[opening]
            depth += current.count { it == '}' } - current.count { it == '{' }
            if (depth < 0) break
            opening--
        }
        val indent = lines.getOrNull(opening)?.takeWhile { it == ' ' || it == '\t' } ?: return null
        val current = text.takeWhile { it == ' ' || it == '\t' }
        return if (current != indent) edit(line, 0, current.length, indent) else null
    }

    private fun edit(line: Int, start: Int, end: Int, newText: String): JsonObject =
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
            put("newText", newText)
        }
}
