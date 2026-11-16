package org.example.kotlineditor.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent

// JSON of the teams of agents, and of the skills an agent of a team can be given.

private const val ID = "id"
private const val NAME = "name"
private const val DESCRIPTION = "description"
private const val SKILLS = "skills"

/** An agent of a team. */
fun TeamAgent.toJson(): JsonObject =
    buildJsonObject {
        put(ID, id)
        put(NAME, name)
        put(DESCRIPTION, description)
        put("agent", agent.toJson())
        put("settings", JsonObject(settings.mapValues { JsonPrimitive(it.value) }))
        put(SKILLS, JsonArray(skills.map { JsonPrimitive(it) }))
        put("systemPrompt", systemPrompt)
    }

/** Reads an agent of a team. */
fun JsonElement.teamAgent(): TeamAgent {
    val json = jsonObject
    val settings = json["settings"]?.jsonObject.orEmpty().mapValues { it.value.string() }
    val skills = json[SKILLS]?.strings().orEmpty()
    return TeamAgent(
        id = json.string(ID),
        name = json.string(NAME),
        description = json.stringOrNull(DESCRIPTION).orEmpty(),
        agent = json.field("agent").agentCommand(),
        settings = settings,
        skills = skills,
        systemPrompt = json.stringOrNull("systemPrompt").orEmpty(),
    )
}

/** A team. */
fun Team.toJson(): JsonObject =
    buildJsonObject {
        put(ID, id)
        put(NAME, name)
        put("manager", manager.toJson())
        put("members", JsonArray(members.map { it.toJson() }))
    }

/** Reads a team. */
fun JsonElement.team(): Team {
    val json = jsonObject
    val members = json.field("members").jsonArray.map { it.teamAgent() }
    return Team(json.string(ID), json.string(NAME), json.field("manager").teamAgent(), members)
}

/** Skills of agents. */
fun List<AgentSkill>.skillsToJson(): JsonArray =
    JsonArray(
        map { skill ->
            buildJsonObject {
                put(ID, skill.id)
                put(NAME, skill.name)
                put(DESCRIPTION, skill.description)
                put("project", skill.project)
            }
        },
    )

/** Reads skills of agents. */
fun JsonElement.agentSkills(): List<AgentSkill> =
    jsonArray.map { element ->
        val json = element.jsonObject
        AgentSkill(json.string(ID), json.string(NAME), json.string(DESCRIPTION), json.bool("project"))
    }

/** The teams written as JSON, for the preferences. */
fun List<Team>.teamsToText(): String = JsonArray(map { it.toJson() }).toString()

/** The teams [teamsToText] wrote; a damaged team is ignored, damaged text gives none. */
fun teamsOfText(text: String?): List<Team> {
    val elements = if (text.isNullOrBlank()) null else parsedArray(text)
    return elements.orEmpty().mapNotNull { element -> runCatching { element.team() }.getOrNull() }
}

private fun parsedArray(text: String): JsonArray? {
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return parsed as? JsonArray
}
