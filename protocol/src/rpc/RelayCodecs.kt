package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.RelayMember
import org.example.kotlineditor.model.RelayTeam

private const val NAME = "name"

/** A team served in relay mode. */
fun RelayTeam.toJson(): JsonObject =
    buildJsonObject {
        put("manager", manager.toJson())
        put("members", JsonArray(members.map { it.toJson() }))
    }

private fun RelayMember.toJson(): JsonObject =
    buildJsonObject {
        put(NAME, name)
        put("prompt", prompt)
        put("model", model)
        put("effort", effort)
    }

/** Reads a team served in relay mode. */
fun JsonElement.relayTeam(): RelayTeam {
    val json = jsonObject
    return RelayTeam(json.field("manager").relayMember(), json.field("members").jsonArray.map { it.relayMember() })
}

private fun JsonElement.relayMember(): RelayMember {
    val json = jsonObject
    val prompt = json.stringOrNull("prompt").orEmpty()
    val model = json.stringOrNull("model").orEmpty()
    return RelayMember(json.string(NAME), prompt, model, json.stringOrNull("effort").orEmpty())
}
