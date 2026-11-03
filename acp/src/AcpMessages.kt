package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.rpc.Protocol
import java.io.IOException

// The messages this client sends (ACP version 1), and what it reads of the agent's answer to `initialize`.

/** The version of ACP this client speaks. */
internal const val ACP_VERSION = 1

/** The field naming the session of a request or a notification. */
internal const val SESSION_ID = "sessionId"

/** What an agent said about itself when it started. */
internal class AgentInfo(
    /** Its name and version, for people (`Claude Code 1.2`); null when it did not say. */
    val name: String?,
    /** How the user can log in to it, one line each. */
    val authMethods: List<String>,
    /** What it can do with the sessions it keeps. */
    val sessions: SessionCapabilities = SessionCapabilities(),
)

/** What an agent can do with the sessions it keeps, from its capabilities. */
internal data class SessionCapabilities(
    /** `session/load`: reopens a session and replays its history. */
    val load: Boolean = false,
    /** `session/list`: lists its sessions. */
    val list: Boolean = false,
    /** `session/resume`: reopens a session without replaying it. */
    val resume: Boolean = false,
    /** `session/close`: frees a session. */
    val close: Boolean = false,
    /** `session/delete`: deletes a session and its history. */
    val delete: Boolean = false,
)

/** `initialize`: the version and the capabilities of this client, which serves file reads and writes. */
internal fun initializeParams(): JsonObject =
    buildJsonObject {
        put("protocolVersion", ACP_VERSION)
        putJsonObject("clientCapabilities") {
            putJsonObject("fs") {
                put("readTextFile", true)
                put("writeTextFile", true)
            }
            put("terminal", true)
        }
        putJsonObject("clientInfo") {
            put("name", "kotlin-editor")
            put("title", "Kotlin Editor")
            put("version", Protocol.APP_VERSION)
        }
    }

/** Reads the answer to `initialize`; fails when the agent speaks another version of ACP. */
internal fun agentInfo(answer: JsonObject?): AgentInfo {
    val protocolVersion = answer?.get("protocolVersion")?.jsonPrimitive?.intOrNull
    if (protocolVersion != ACP_VERSION) {
        throw IOException("The agent speaks ACP version $protocolVersion, not $ACP_VERSION")
    }
    val agent = answer["agentInfo"].obj()
    val title = agent?.text("title") ?: agent?.text("name")
    val agentVersion = agent?.text("version")
    val name = title?.let { listOfNotNull(it, agentVersion).joinToString(" ") }
    val methods =
        answer["authMethods"].array().mapNotNull { it.obj() }.map { method ->
            listOfNotNull(method.text("name"), method.text("description")).joinToString(": ")
        }
    return AgentInfo(name, methods, sessionCapabilities(answer["agentCapabilities"].obj()))
}

// `loadSession: true`, and the objects of `sessionCapabilities`: present means supported.
private fun sessionCapabilities(capabilities: JsonObject?): SessionCapabilities {
    val load = capabilities?.get("loadSession")?.jsonPrimitive?.booleanOrNull ?: false
    val sessions = capabilities?.get("sessionCapabilities").obj()
    return SessionCapabilities(
        load = load,
        list = sessions.has("list"),
        resume = sessions.has("resume"),
        close = sessions.has("close"),
        delete = sessions.has("delete"),
    )
}

private fun JsonObject?.has(name: String): Boolean = this?.get(name).obj() != null

/** `session/new` in [directory], without MCP servers. */
internal fun newSessionParams(directory: String): JsonObject =
    buildJsonObject {
        put("cwd", directory)
        put("mcpServers", JsonArray(emptyList()))
    }

/** `session/load` and `session/resume`: reopens [session] in [directory], without MCP servers. */
internal fun reopenParams(session: String, directory: String): JsonObject =
    buildJsonObject {
        put(SESSION_ID, session)
        put("cwd", directory)
        put("mcpServers", JsonArray(emptyList()))
    }

/** `session/list` of the sessions working in [directory], from [cursor] on. */
internal fun listParams(directory: String, cursor: String?): JsonObject =
    buildJsonObject {
        put("cwd", directory)
        if (cursor != null) put("cursor", cursor)
    }

/** A request about [session] alone: `session/close`, `session/delete`. */
internal fun sessionParams(session: String): JsonObject = buildJsonObject { put(SESSION_ID, session) }

/** `session/prompt`: [text] to [session]. */
internal fun promptParams(session: String, text: String): JsonObject {
    val block =
        buildJsonObject {
            put("type", "text")
            put("text", text)
        }
    return buildJsonObject {
        put(SESSION_ID, session)
        put("prompt", buildJsonArray { add(block) })
    }
}
