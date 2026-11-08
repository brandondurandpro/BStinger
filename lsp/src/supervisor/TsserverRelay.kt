package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.example.kotlineditor.model.Languages

/**
 * What the Vue server asks of the TypeScript one. In hybrid mode `vue-language-server` has no TypeScript of its own:
 * it sends `tsserver/request` notifications `[[id, command, args]]`, and expects `tsserver/response` notifications
 * `[[id, body]]` with what tsserver answered. The client carries them: the request goes to the TypeScript server of
 * the project as `workspace/executeCommand` `typescript.tsserverRequest`, whose answer's `body` goes back.
 */
internal class TsserverRelay(
    private val scope: CoroutineScope,
    private val typescriptServersAround: (root: String) -> List<ManagedServer>,
) {
    /** Carries [request], a `tsserver/request` notification of [from], to the TypeScript server and back. */
    fun relay(from: ManagedServer, request: JsonElement) {
        val entry = (request as? JsonArray)?.firstOrNull() as? JsonArray
        val id = entry?.getOrNull(0)
        val command = (entry?.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        if (id != null && command != null) {
            val arguments = entry.getOrNull(2) ?: JsonNull
            scope.launch {
                val body = askTypescript(from, command, arguments)
                from.notify("tsserver/response", buildJsonArray { add(responseOf(id, body)) })
            }
        }
    }

    private fun responseOf(id: JsonElement, body: JsonElement): JsonArray =
        buildJsonArray {
            add(id)
            add(body)
        }

    // JsonNull when there is no TypeScript server ready or it fails: the Vue server then goes on without.
    private suspend fun askTypescript(from: ManagedServer, command: String, arguments: JsonElement): JsonElement {
        val server = typescriptServersAround(from.root).firstOrNull { it.language == Languages.TYPESCRIPT }
        val params =
            buildJsonObject {
                put("command", "typescript.tsserverRequest")
                putJsonArray("arguments") {
                    add(JsonPrimitive(command))
                    add(arguments)
                    add(
                        buildJsonObject {
                            put("isAsync", true)
                            put("lowPriority", true)
                        },
                    )
                }
            }
        val answer = runCatching { server?.request("workspace/executeCommand", params) }.getOrNull()
        return (answer as? JsonObject)?.get("body") ?: JsonNull
    }
}
