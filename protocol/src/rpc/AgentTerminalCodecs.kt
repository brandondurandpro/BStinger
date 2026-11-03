package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentTerminalOutput

// JSON of what a tool call shows of a command the agent runs.

private const val TEXT = "text"
private const val EXIT_CODE = "exitCode"

/** The end of the output of a command, and its exit code. */
internal fun AgentTerminalOutput.toJson(): JsonObject =
    buildJsonObject {
        put(TEXT, text)
        put(EXIT_CODE, exitCode?.let(::JsonPrimitive) ?: JsonNull)
        put("truncated", truncated)
    }

internal fun JsonElement.terminalOutput(): AgentTerminalOutput? {
    val json = this as? JsonObject ?: return null
    val code = json[EXIT_CODE]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
    return AgentTerminalOutput(json.string(TEXT), code, json.bool("truncated"))
}
