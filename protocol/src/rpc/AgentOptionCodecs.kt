package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue

// JSON of the settings of a session of an agent (its model…).

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
