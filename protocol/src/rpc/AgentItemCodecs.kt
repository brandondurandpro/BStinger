package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentDiff
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentPermissionOption
import org.example.kotlineditor.model.AgentPlanEntry
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus

// JSON of the parts of the transcript items: tool calls, permission requests, diffs, plan steps and options.

private const val ID = "id"
private const val VERSION = "version"
private const val TEXT = "text"
private const val TYPE = "type"
private const val PATH = "path"
private const val STATUS = "status"
private const val NAME = "name"
private const val TITLE = "title"
private const val TERMINAL = "terminal"

internal fun JsonObjectBuilder.tool(item: AgentItem.ToolCall) {
    put(TYPE, "tool")
    put(TITLE, item.title)
    put("kind", item.kind.name)
    put(STATUS, item.status.name)
    put("output", item.output)
    put("diffs", JsonArray(item.diffs.map { it.toJson() }))
    put("locations", item.locations.toJson())
    put(TERMINAL, item.terminal?.toJson() ?: JsonNull)
}

internal fun JsonObjectBuilder.permission(item: AgentItem.Permission) {
    put(TYPE, "permission")
    put(TITLE, item.title)
    put("options", JsonArray(item.options.map { it.toJson() }))
    put("chosen", item.chosen?.let(::JsonPrimitive) ?: JsonNull)
    put("cancelled", item.cancelled)
}

internal fun JsonObject.toolCall(id: String, version: Long) =
    AgentItem.ToolCall(
        id = id,
        version = version,
        title = string(TITLE),
        kind = AgentToolKind.valueOf(string("kind")),
        status = AgentToolStatus.valueOf(string(STATUS)),
        output = string("output"),
        diffs = field("diffs").jsonArray.map { it.diff() },
        locations = field("locations").strings(),
        terminal = get(TERMINAL)?.terminalOutput(),
    )

internal fun JsonObject.permission(id: String, version: Long) =
    AgentItem.Permission(
        id = id,
        version = version,
        title = string(TITLE),
        options = field("options").jsonArray.map { it.permissionOption() },
        chosen = stringOrNull("chosen"),
        cancelled = bool("cancelled"),
    )

internal fun AgentDiff.toJson() =
    buildJsonObject {
        put(PATH, path)
        put("oldText", oldText?.let(::JsonPrimitive) ?: JsonNull)
        put("newText", newText)
    }

internal fun JsonElement.diff(): AgentDiff {
    val json = jsonObject
    return AgentDiff(json.string(PATH), json.stringOrNull("oldText"), json.string("newText"))
}

internal fun AgentPlanEntry.toJson() =
    buildJsonObject {
        put("content", content)
        put(STATUS, status)
    }

internal fun JsonElement.planEntry(): AgentPlanEntry {
    val json = jsonObject
    return AgentPlanEntry(json.string("content"), json.string(STATUS))
}

internal fun AgentPermissionOption.toJson() =
    buildJsonObject {
        put(ID, id)
        put(NAME, name)
        put("kind", kind)
    }

internal fun JsonElement.permissionOption(): AgentPermissionOption {
    val json = jsonObject
    return AgentPermissionOption(json.string(ID), json.string(NAME), json["kind"]?.jsonPrimitive?.content.orEmpty())
}
