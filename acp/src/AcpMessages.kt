package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.AgentAuthMethod
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
    /** How the user can log in to it. */
    val authMethods: List<AgentAuthMethod>,
    /** What it can do with the sessions it keeps. */
    val sessions: SessionCapabilities = SessionCapabilities(),
    /** What a prompt may hold besides text and links. */
    val prompt: PromptCapabilities = PromptCapabilities(),
    /** The kinds of MCP servers it connects to besides `stdio`. */
    val mcp: McpCapabilities = McpCapabilities(),
)

/** The MCP transports an agent supports besides `stdio`, which every agent does. */
internal data class McpCapabilities(val http: Boolean = false, val sse: Boolean = false)

/** What a prompt may hold besides text and links to files, which every agent takes. */
internal data class PromptCapabilities(
    /** Images. */
    val image: Boolean = false,
    /** Files and lines sent with their text (embedded resources). */
    val embedded: Boolean = false,
    /** Sounds (`audio`). */
    val audio: Boolean = false,
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
    /** `session/fork`: copies a session under a new one. */
    val fork: Boolean = false,
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
        answer["authMethods"].array().mapNotNull { it.obj() }.mapNotNull { method ->
            val id = method.text("id")
            id?.let { AgentAuthMethod(it, method.text("name") ?: it, method.text("description")) }
        }
    val capabilities = answer["agentCapabilities"].obj()
    val mcp = capabilities?.get("mcpCapabilities").obj()
    val mcpKinds = McpCapabilities(mcp.flag("http"), mcp.flag("sse"))
    return AgentInfo(name, methods, sessionCapabilities(capabilities), promptCapabilities(capabilities), mcpKinds)
}

private fun promptCapabilities(capabilities: JsonObject?): PromptCapabilities {
    val prompt = capabilities?.get("promptCapabilities").obj()
    return PromptCapabilities(prompt.flag("image"), prompt.flag("embeddedContext"), prompt.flag("audio"))
}

private fun JsonObject?.flag(name: String): Boolean = this?.get(name)?.jsonPrimitive?.booleanOrNull ?: false

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
        fork = sessions.has("fork"),
    )
}

private fun JsonObject?.has(name: String): Boolean = this?.get(name).obj() != null

/** `session/new` in [directory], with the MCP servers the user configured ([McpServers]). */
internal fun newSessionParams(directory: String, mcp: McpCapabilities): JsonObject =
    buildJsonObject {
        put("cwd", directory)
        put("mcpServers", McpServers.forAgent(mcp))
    }

/** `session/load` and `session/resume`: reopens [session] in [directory], with the same MCP servers. */
internal fun reopenParams(session: String, directory: String, mcp: McpCapabilities): JsonObject =
    buildJsonObject {
        put(SESSION_ID, session)
        put("cwd", directory)
        put("mcpServers", McpServers.forAgent(mcp))
    }

/** `session/list` of the sessions working in [directory], from [cursor] on. */
internal fun listParams(directory: String, cursor: String?): JsonObject =
    buildJsonObject {
        put("cwd", directory)
        if (cursor != null) put("cursor", cursor)
    }

/** A request about [session] alone: `session/close`, `session/delete`. */
internal fun sessionParams(session: String): JsonObject = buildJsonObject { put(SESSION_ID, session) }

/** `session/prompt`: [blocks] (the text of the user, then what it joined) to [session]. */
internal fun promptParams(session: String, blocks: List<JsonObject>): JsonObject =
    buildJsonObject {
        put(SESSION_ID, session)
        put("prompt", JsonArray(blocks))
    }
