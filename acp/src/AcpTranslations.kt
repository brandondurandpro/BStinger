package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.example.kotlineditor.model.AgentDiff
import org.example.kotlineditor.model.AgentPlanEntry
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus

// Reading the JSON of ACP (version 1): content blocks, tool calls, plans and permission options. Unknown values are
// read as the closest known one, never as an error: agents add fields and kinds faster than the protocol.

private const val CONTENT = "content"

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

/**
 * The text of a content block: its text, or a short description of what it holds (an image, a resource), since the
 * transcript shows text only.
 */
internal fun JsonObject.contentText(): String =
    when (text("type")) {
        "text" -> text("text").orEmpty()
        "image" -> "[image]"
        "audio" -> "[audio]"
        "resource_link" -> text("uri").orEmpty()
        "resource" -> get("resource").obj()?.let { it.text("text") ?: it.text("uri") }.orEmpty()
        else -> ""
    }

internal fun toolKind(kind: String?): AgentToolKind =
    when (kind) {
        "read" -> AgentToolKind.Read
        "edit" -> AgentToolKind.Edit
        "delete" -> AgentToolKind.Delete
        "move" -> AgentToolKind.Move
        "search" -> AgentToolKind.Search
        "execute" -> AgentToolKind.Execute
        "think" -> AgentToolKind.Think
        "fetch" -> AgentToolKind.Fetch
        else -> AgentToolKind.Other
    }

internal fun toolStatus(status: String?): AgentToolStatus? =
    when (status) {
        "pending" -> AgentToolStatus.Pending
        "in_progress" -> AgentToolStatus.Running
        "completed" -> AgentToolStatus.Completed
        "failed" -> AgentToolStatus.Failed
        else -> null
    }

/**
 * The output, the diffs and the terminals (their ids) of the `content` of a tool call, null when the update does not
 * change them.
 */
internal class ToolContent(val output: String, val diffs: List<AgentDiff>, val terminals: List<String>)

internal fun JsonObject.toolContent(): ToolContent? {
    val content = get(CONTENT) as? JsonArray ?: return null
    val texts = mutableListOf<String>()
    val diffs = mutableListOf<AgentDiff>()
    val terminals = mutableListOf<String>()
    for (entry in content.mapNotNull { it.obj() }) {
        when (entry.text("type")) {
            CONTENT -> entry[CONTENT].obj()?.contentText()?.let(texts::add)
            "diff" -> entry.diff()?.let(diffs::add)
            "terminal" -> entry.text("terminalId")?.let(terminals::add)
        }
    }
    return ToolContent(texts.filter { it.isNotEmpty() }.joinToString("\n"), diffs, terminals)
}

private fun JsonObject.diff(): AgentDiff? {
    val path = text("path") ?: return null
    return AgentDiff(path, text("oldText"), text("newText").orEmpty())
}

/** The paths of the `locations` of a tool call, null when the update does not change them. */
internal fun JsonObject.toolLocations(): List<String>? {
    val locations = get("locations") as? JsonArray ?: return null
    return locations.mapNotNull { it.obj()?.text("path") }
}

internal fun JsonObject.planEntries(): List<AgentPlanEntry> =
    get("entries").array().mapNotNull { it.obj() }.map { entry ->
        AgentPlanEntry(entry.text(CONTENT).orEmpty(), entry.text("status") ?: "pending")
    }
