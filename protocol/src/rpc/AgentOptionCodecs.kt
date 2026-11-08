package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentAuthMethod
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.model.AgentSlashCommand

// JSON of the settings of a session of an agent (its model…), and of the commands it offers.

private const val VALUE = "value"
private const val NAME = "name"
private const val DESCRIPTION = "description"

/** The settings of a session. */
fun List<AgentOption>.toJson(): JsonArray =
    JsonArray(
        map { option ->
            buildJsonObject {
                put("id", option.id)
                put(NAME, option.name)
                put("category", option.category.orNull())
                put("current", option.current)
                put("values", JsonArray(option.values.map { it.toJson() }))
            }
        },
    )

private fun AgentOptionValue.toJson() =
    buildJsonObject {
        put(VALUE, value)
        put(NAME, name)
        put(DESCRIPTION, description.orNull())
    }

/** Reads the settings of a session. */
fun JsonElement.agentOptions(): List<AgentOption> =
    jsonArray.map { element ->
        val json = element.jsonObject
        AgentOption(
            id = json.string("id"),
            name = json.string(NAME),
            category = json.stringOrNull("category"),
            current = json.string("current"),
            values = json.field("values").jsonArray.map { it.agentOptionValue() },
        )
    }

private fun JsonElement.agentOptionValue(): AgentOptionValue {
    val json = jsonObject
    return AgentOptionValue(json.string(VALUE), json.string(NAME), json.stringOrNull(DESCRIPTION))
}

/** The slash commands of an agent. */
fun List<AgentSlashCommand>.commandsToJson(): JsonArray =
    JsonArray(
        map { command ->
            buildJsonObject {
                put(NAME, command.name)
                put(DESCRIPTION, command.description)
                put("hint", command.hint.orNull())
            }
        },
    )

/** Reads the slash commands of an agent. */
fun JsonElement.agentSlashCommands(): List<AgentSlashCommand> =
    jsonArray.map { element ->
        val json = element.jsonObject
        AgentSlashCommand(json.string(NAME), json.string(DESCRIPTION), json.stringOrNull("hint"))
    }

/** The ways to log in to an agent. */
fun List<AgentAuthMethod>.authMethodsToJson(): JsonArray =
    JsonArray(
        map { method ->
            buildJsonObject {
                put("id", method.id)
                put(NAME, method.name)
                put(DESCRIPTION, method.description.orNull())
            }
        },
    )

/** Reads the ways to log in to an agent. */
fun JsonElement.agentAuthMethods(): List<AgentAuthMethod> =
    jsonArray.map { element ->
        val json = element.jsonObject
        AgentAuthMethod(json.string("id"), json.string(NAME), json.stringOrNull(DESCRIPTION))
    }
