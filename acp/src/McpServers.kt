package org.example.kotlineditor.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.io.IOException

/**
 * The MCP servers the agents are given in every session (`mcpServers` of `session/new`): the array in the file
 * `mcp-servers.json` of the data directory of the IDE, in the format of ACP (`name`, `command`, `args`, `env` for a
 * `stdio` server; `type`, `name`, `url`, `headers` for an `http` or `sse` one). No file means no server.
 */
internal object McpServers {
    /** The file read at each session; tests point it elsewhere. */
    @Volatile
    var file: File = File(File(System.getProperty("user.home"), ".kotlin-editor"), "mcp-servers.json")

    /** The servers of the file the agent can connect to: a remote one only when it announced that transport. */
    fun forAgent(capabilities: McpCapabilities): JsonArray {
        val servers = read()
        val usable = servers.filter { server -> supported(server, capabilities) }
        return JsonArray(usable)
    }

    private fun read(): List<JsonElement> {
        val text =
            try {
                file.takeIf { it.isFile }?.readText()
            } catch (_: IOException) {
                null
            }
        val parsed = text?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
        return parsed?.filter { it is JsonObject }.orEmpty()
    }

    private fun supported(server: JsonElement, capabilities: McpCapabilities): Boolean {
        val type = (server as? JsonObject)?.text("type")
        return when (type) {
            "http" -> capabilities.http
            "sse" -> capabilities.sse
            else -> true
        }
    }
}
