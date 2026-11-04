package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * What the [FakeAgent] answers to `context`: `Context: ` then each block joined to the prompt, in order, or `none`:
 * `link Main.kt`, `file Main.kt (3 lines)`, `lines Main.kt#L2-L3 (2 lines)`, `image image/png (68 bytes)`,
 * `text (4 lines)`.
 */
fun describeContext(blocks: List<JsonObject>): String {
    val described = blocks.map(::describe)
    val listed = described.ifEmpty { listOf("none") }.joinToString("; ")
    return "Context: $listed."
}

private fun describe(block: JsonObject): String =
    when (block.string("type")) {
        "resource_link" -> "link ${block.string("name")}"
        "resource" -> resource(block["resource"]?.jsonObject)
        "image" -> image(block)
        "text" -> "text (${lineCount(block.string("text"))} lines)"
        else -> "unknown ${block.string("type")}"
    }

private fun resource(resource: JsonObject?): String {
    val uri = resource?.string("uri").orEmpty()
    val name = uri.substringAfterLast('/')
    val lines = lineCount(resource?.string("text"))
    val kind = if ('#' in name) "lines" else "file"
    return "$kind $name ($lines lines)"
}

private fun image(block: JsonObject): String {
    val bytes = Base64.getDecoder().decode(block.string("data").orEmpty())
    return "image ${block.string("mimeType")} (${bytes.size} bytes)"
}

private fun lineCount(text: String?): Int = text.orEmpty().trimEnd('\n').lines().size

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
