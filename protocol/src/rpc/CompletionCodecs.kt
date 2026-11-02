package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionKind
import org.example.kotlineditor.model.CompletionResult

// JSON of the completion answers.

/** A completion answer; the insert text travels only when it differs from the label. */
fun CompletionResult.toJson(): JsonObject =
    buildJsonObject {
        put("from", replaceFrom)
        put("incomplete", incomplete)
        put("ready", ready)
        put(
            "items",
            JsonArray(
                items.map { item ->
                    buildJsonObject {
                        put("l", item.label)
                        put("k", item.kind.name)
                        item.detail?.let { put("d", it) }
                        if (item.insertText != item.label) put("i", item.insertText)
                    }
                },
            ),
        )
    }

/** Reads a completion answer. */
fun JsonElement.completionResult(): CompletionResult {
    val result = jsonObject
    val itemElements = result.getValue("items").jsonArray
    val items = itemElements.map { it.jsonObject.completionItem() }
    return CompletionResult(
        replaceFrom = result.int("from"),
        items = items,
        incomplete = result.bool("incomplete"),
        ready = result.bool("ready"),
    )
}

private fun JsonObject.completionItem(): CompletionItem {
    val label = string("l")
    val kindName = string("k")
    val knownKind = CompletionKind.entries.firstOrNull { it.name == kindName }
    val kind = knownKind ?: CompletionKind.Other
    // The insert text travels only when it differs from the label.
    val insertText = stringOrNull("i") ?: label
    return CompletionItem(label, kind, stringOrNull("d"), insertText)
}
