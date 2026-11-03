package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
            put("terminal", false)
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
    return AgentInfo(name, methods)
}

/** `session/new` in [directory], without MCP servers. */
internal fun newSessionParams(directory: String): JsonObject =
    buildJsonObject {
        put("cwd", directory)
        put("mcpServers", JsonArray(emptyList()))
    }

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
