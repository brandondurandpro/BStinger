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
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentDiff
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentPermissionOption
import org.example.kotlineditor.model.AgentPlanEntry
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState

// JSON of agent mode: how an agent is started, and the transcript of a conversation.

private const val ID = "id"
private const val VERSION = "version"
private const val TEXT = "text"
private const val TYPE = "type"
private const val PATH = "path"
private const val STATUS = "status"
private const val NAME = "name"

/** An agent command line. */
fun AgentCommand.toJson(): JsonObject =
    buildJsonObject {
        put("command", command.toJson())
        put("environment", JsonObject(environment.mapValues { JsonPrimitive(it.value) }))
    }

/** Reads an agent command line. */
fun JsonElement.agentCommand(): AgentCommand {
    val json = jsonObject
    val environment = json["environment"]?.jsonObject.orEmpty().mapValues { it.value.string() }
    return AgentCommand(json.field("command").strings(), environment)
}

/** A transcript. */
fun AgentTranscript.toJson(): JsonObject =
    buildJsonObject {
        put(VERSION, version)
        put("epoch", epoch)
        put("known", known)
        put("state", state.name)
        put("items", JsonArray(items.map { it.toJson() }))
        put("agentName", agentName?.let(::JsonPrimitive) ?: JsonNull)
    }

/** Reads a transcript. */
fun JsonElement.agentTranscript(): AgentTranscript {
    val json = jsonObject
    return AgentTranscript(
        version = json.long(VERSION),
        epoch = json.string("epoch"),
        known = json.bool("known"),
        state = AgentTurnState.valueOf(json.string("state")),
        items = json.field("items").jsonArray.map { it.agentItem() },
        agentName = json.stringOrNull("agentName"),
    )
}

private fun AgentItem.toJson(): JsonObject =
    buildJsonObject {
        put(ID, id)
        put(VERSION, version)
        when (val item = this@toJson) {
            is AgentItem.UserText -> text("user", item.text)
            is AgentItem.AgentText -> text("agent", item.text)
            is AgentItem.Thought -> text("thought", item.text)
            is AgentItem.ToolCall -> tool(item)
            is AgentItem.Plan -> {
                put(TYPE, "plan")
                put("entries", JsonArray(item.entries.map { it.toJson() }))
            }
            is AgentItem.Permission -> permission(item)
            is AgentItem.Notice -> {
                text("notice", item.text)
                put("error", item.error)
            }
        }
    }

private fun JsonObjectBuilder.text(type: String, text: String) {
    put(TYPE, type)
    put(TEXT, text)
}

private fun JsonObjectBuilder.tool(item: AgentItem.ToolCall) {
    put(TYPE, "tool")
    put("title", item.title)
    put("kind", item.kind.name)
    put(STATUS, item.status.name)
    put("output", item.output)
    put("diffs", JsonArray(item.diffs.map { it.toJson() }))
    put("locations", item.locations.toJson())
}

private fun JsonObjectBuilder.permission(item: AgentItem.Permission) {
    put(TYPE, "permission")
    put("title", item.title)
    put("options", JsonArray(item.options.map { it.toJson() }))
    put("chosen", item.chosen?.let(::JsonPrimitive) ?: JsonNull)
    put("cancelled", item.cancelled)
}

private fun JsonElement.agentItem(): AgentItem {
    val json = jsonObject
    val id = json.string(ID)
    val version = json.long(VERSION)
    return when (val type = json.string(TYPE)) {
        "user" -> AgentItem.UserText(id, version, json.string(TEXT))
        "agent" -> AgentItem.AgentText(id, version, json.string(TEXT))
        "thought" -> AgentItem.Thought(id, version, json.string(TEXT))
        "tool" -> json.toolCall(id, version)
        "plan" -> AgentItem.Plan(id, version, json.field("entries").jsonArray.map { it.planEntry() })
        "permission" -> json.permission(id, version)
        "notice" -> AgentItem.Notice(id, version, json.string(TEXT), json.bool("error"))
        else -> throw InvalidParamsException("Unknown agent item $type")
    }
}

private fun JsonObject.toolCall(id: String, version: Long) =
    AgentItem.ToolCall(
        id = id,
        version = version,
        title = string("title"),
        kind = AgentToolKind.valueOf(string("kind")),
        status = AgentToolStatus.valueOf(string(STATUS)),
        output = string("output"),
        diffs = field("diffs").jsonArray.map { it.diff() },
        locations = field("locations").strings(),
    )

private fun JsonObject.permission(id: String, version: Long) =
    AgentItem.Permission(
        id = id,
        version = version,
        title = string("title"),
        options = field("options").jsonArray.map { it.permissionOption() },
        chosen = stringOrNull("chosen"),
        cancelled = bool("cancelled"),
    )

private fun AgentDiff.toJson() =
    buildJsonObject {
        put(PATH, path)
        put("oldText", oldText?.let(::JsonPrimitive) ?: JsonNull)
        put("newText", newText)
    }

private fun JsonElement.diff(): AgentDiff {
    val json = jsonObject
    return AgentDiff(json.string(PATH), json.stringOrNull("oldText"), json.string("newText"))
}

private fun AgentPlanEntry.toJson() =
    buildJsonObject {
        put("content", content)
        put(STATUS, status)
    }

private fun JsonElement.planEntry(): AgentPlanEntry {
    val json = jsonObject
    return AgentPlanEntry(json.string("content"), json.string(STATUS))
}

private fun AgentPermissionOption.toJson() =
    buildJsonObject {
        put(ID, id)
        put(NAME, name)
        put("kind", kind)
    }

private fun JsonElement.permissionOption(): AgentPermissionOption {
    val json = jsonObject
    return AgentPermissionOption(json.string(ID), json.string(NAME), json["kind"]?.jsonPrimitive?.content.orEmpty())
}
