package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What one session gets besides the MCP servers of the user: [mcpServers] (entries of `mcpServers`, such as the
 * `delegate` tool of a manager) and a `_meta` object ([meta]) for the agent. The client sends no other `_meta`, so
 * there is nothing to merge it into: it goes as it is.
 */
internal class SessionExtras(val mcpServers: List<JsonObject> = emptyList(), val meta: JsonObject? = null) {
    /** The extras of the sessions that have none. */
    companion object {
        val None = SessionExtras()
    }
}

// The servers of the user, then those of the extras (an entry of a kind the agent does not support is left out).
internal fun SessionExtras.servers(mcp: McpCapabilities): JsonArray {
    val configured = McpServers.forAgent(mcp)
    val added = mcpServers.filter { McpServers.supported(it, mcp) }
    return JsonArray(configured + added)
}
