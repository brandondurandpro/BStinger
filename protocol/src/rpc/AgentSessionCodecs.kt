package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentSession
import org.example.kotlineditor.model.AgentSessions

// JSON of the sessions an agent keeps: the history of the conversations.

private const val SESSIONS = "sessions"

/** The sessions of an agent. */
fun AgentSessions.toJson(): JsonObject =
    buildJsonObject {
        put("supported", supported)
        put(SESSIONS, JsonArray(sessions.map { it.toJson() }))
    }

/** Reads the sessions of an agent. */
fun JsonElement.agentSessions(): AgentSessions {
    val json = jsonObject
    return AgentSessions(json.bool("supported"), json.field(SESSIONS).jsonArray.map { it.agentSession() })
}

private fun AgentSession.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("directory", directory)
        put("title", title.orNull())
        put("updatedAt", updatedAt.orNull())
        put("branch", branch.orNull())
    }

private fun JsonElement.agentSession(): AgentSession {
    val json = jsonObject
    return AgentSession(
        id = json.string("id"),
        directory = json.string("directory"),
        title = json.stringOrNull("title"),
        updatedAt = json.stringOrNull("updatedAt"),
        branch = json.stringOrNull("branch"),
    )
}
