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
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
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
private const val EPOCH = "epoch"
private const val KNOWN = "known"
private const val STATE = "state"
private const val ITEMS = "items"
private const val AGENT_NAME = "agentName"
private const val AGENT_OF = "agentOf"
private const val COMMAND = "command"
private const val ENVIRONMENT = "environment"
private const val REGISTRY = "registry"
private const val SESSION = "session"
private const val TITLE = "title"
private const val REPLAYED = "replayed"
private const val OPTIONS = "options"
private const val COMMANDS = "commands"
private const val CONTEXT = "context"

/** An agent command line. */
fun AgentCommand.toJson(): JsonObject =
    buildJsonObject {
        put(COMMAND, command.toJson())
        put(ENVIRONMENT, JsonObject(environment.mapValues { JsonPrimitive(it.value) }))
        put(REGISTRY, registry.orNull())
    }

/** Reads an agent command line. */
fun JsonElement.agentCommand(): AgentCommand {
    val json = jsonObject
    val environment = json[ENVIRONMENT]?.jsonObject.orEmpty().mapValues { it.value.string() }
    return AgentCommand(json.field(COMMAND).strings(), environment, json.stringOrNull(REGISTRY))
}

/** A transcript. */
fun AgentTranscript.toJson(): JsonObject =
    buildJsonObject {
        put(VERSION, version)
        put(EPOCH, epoch)
        put(KNOWN, known)
        put(STATE, state.name)
        put(ITEMS, JsonArray(items.map { it.toJson() }))
        put(AGENT_NAME, agentName.orNull())
        put(AGENT_OF, agent?.toJson() ?: JsonNull)
        put(SESSION, session.orNull())
        put(TITLE, title.orNull())
        put(REPLAYED, replayed)
        put(OPTIONS, options.toJson())
        put(COMMANDS, commands.commandsToJson())
    }

/** A string, or JSON null. */
internal fun String?.orNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

/** Reads a transcript. */
fun JsonElement.agentTranscript(): AgentTranscript {
    val json = jsonObject
    return AgentTranscript(
        version = json.long(VERSION),
        epoch = json.string(EPOCH),
        known = json.bool(KNOWN),
        state = AgentTurnState.valueOf(json.string(STATE)),
        items = json.field(ITEMS).jsonArray.map { it.agentItem() },
        agentName = json.stringOrNull(AGENT_NAME),
        agent = json[AGENT_OF]?.takeIf { it != JsonNull }?.agentCommand(),
        session = json.stringOrNull(SESSION),
        title = json.stringOrNull(TITLE),
        replayed = json[REPLAYED]?.let { json.bool(REPLAYED) } ?: false,
        options = json[OPTIONS]?.agentOptions().orEmpty(),
        commands = json[COMMANDS]?.agentSlashCommands().orEmpty(),
    )
}

private fun AgentItem.toJson(): JsonObject =
    buildJsonObject {
        put(ID, id)
        put(VERSION, version)
        when (val item = this@toJson) {
            is AgentItem.UserText -> {
                text("user", item.text)
                if (item.context.isNotEmpty()) put(CONTEXT, JsonArray(item.context.map(::JsonPrimitive)))
            }

            is AgentItem.AgentText -> {
                text("agent", item.text)
            }

            is AgentItem.Thought -> {
                text("thought", item.text)
            }

            is AgentItem.ToolCall -> {
                tool(item)
            }

            is AgentItem.Plan -> {
                put(TYPE, "plan")
                put("entries", JsonArray(item.entries.map { it.toJson() }))
            }

            is AgentItem.Permission -> {
                permission(item)
            }

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

private fun JsonElement.agentItem(): AgentItem {
    val json = jsonObject
    val id = json.string(ID)
    val version = json.long(VERSION)
    return when (val type = json.string(TYPE)) {
        "user" -> AgentItem.UserText(id, version, json.string(TEXT), json[CONTEXT]?.strings().orEmpty())
        "agent" -> AgentItem.AgentText(id, version, json.string(TEXT))
        "thought" -> AgentItem.Thought(id, version, json.string(TEXT))
        "tool" -> json.toolCall(id, version)
        "plan" -> AgentItem.Plan(id, version, json.field("entries").jsonArray.map { it.planEntry() })
        "permission" -> json.permission(id, version)
        "notice" -> AgentItem.Notice(id, version, json.string(TEXT), json.bool("error"))
        else -> throw InvalidParamsException("Unknown agent item $type")
    }
}
