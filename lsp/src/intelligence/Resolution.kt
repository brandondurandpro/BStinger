package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.example.kotlineditor.lsp.supervisor.ManagedServer

/**
 * The resolution of the items of a list a server leaves incomplete (code lenses without a command, links without a
 * target) with [method], when its [provider] capability has `resolveProvider`: all at once, the first [max] only.
 */
internal class Resolution(
    private val server: ManagedServer,
    private val provider: String,
    private val method: String,
    private val max: Int,
) {
    /** [items], those [incomplete] replaced by what the server resolved them to (unchanged when it could not). */
    suspend fun resolved(
        items: List<JsonObject>,
        request: suspend (String, JsonObject) -> JsonElement?,
        incomplete: (JsonObject) -> Boolean,
    ): List<JsonObject> {
        val capability = server.capability(provider) as? JsonObject
        val resolves = (capability?.get("resolveProvider") as? JsonPrimitive)?.booleanOrNull == true
        if (!resolves) return items
        var left = max
        return coroutineScope {
            val answers =
                items.map { item ->
                    val resolve = incomplete(item) && left-- > 0
                    async { if (resolve) request(method, item) as? JsonObject else null }
                }
            answers.awaitAll().zip(items) { answer, item -> answer ?: item }
        }
    }
}
