package org.example.kotlineditor.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.CatalogSkill
import org.example.kotlineditor.model.SkillCatalogListing
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent

// JSON of the teams of agents, and of the skills an agent of a team can be given.

private const val ID = "id"
private const val NAME = "name"
private const val DESCRIPTION = "description"
private const val SKILLS = "skills"
private const val CAN_ASK = "canAsk"
private const val OWNER = "owner"

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
        put(CAN_ASK, canAsk)
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
        canAsk = json[CAN_ASK]?.jsonPrimitive?.booleanOrNull ?: false,
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
                put(OWNER, skill.owner)
            }
        },
    )

/** Reads skills of agents. */
fun JsonElement.agentSkills(): List<AgentSkill> =
    jsonArray.map { element ->
        val json = element.jsonObject
        AgentSkill(json.string(ID), json.string(NAME), json.string(DESCRIPTION), json.bool("project"), json.stringOrNull(OWNER).orEmpty())
    }

/** The listing of the skill catalog. */
fun SkillCatalogListing.toJson(): JsonObject =
    buildJsonObject {
        put(
            "skills",
            JsonArray(
                skills.map { skill ->
                    buildJsonObject {
                        put("source", skill.source)
                        put("path", skill.path)
                        put(NAME, skill.name)
                        put(DESCRIPTION, skill.description)
                        put("installed", skill.installed)
                    }
                },
            ),
        )
        put("failures", JsonObject(failures.mapValues { JsonPrimitive(it.value) }))
    }

/** Reads the listing of the skill catalog. */
fun JsonElement.skillCatalogListing(): SkillCatalogListing {
    val json = jsonObject
    val skills =
        json.field("skills").jsonArray.map { element ->
            val skill = element.jsonObject
            CatalogSkill(skill.string("source"), skill.string("path"), skill.string(NAME), skill.string(DESCRIPTION), skill.bool("installed"))
        }
    val failures = json.field("failures").jsonObject.mapValues { it.value.jsonPrimitive.content }
    return SkillCatalogListing(skills, failures)
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
