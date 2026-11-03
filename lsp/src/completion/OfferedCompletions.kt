package org.example.kotlineditor.lsp.completion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.example.kotlineditor.lsp.intelligence.contentsMarkdown
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The last completion answer of a session, with the server's items, to resolve them. */
private class Offered(val token: String, val server: ManagedServer, val items: List<JsonObject>)

/**
 * The completion answers whose items can be resolved (`completionItem/resolve`): the last one of each session,
 * named by a token, so that the documentation of the selected item is asked to the server that proposed it.
 */
internal class OfferedCompletions(private val timeoutMs: Long) {
    private val bySession = ConcurrentHashMap<String, Offered>()
    private val tokens = AtomicLong()

    /** Keeps the [items] [server] answered to [session]; the token names them, empty when it cannot resolve. */
    fun offer(session: String, server: ManagedServer, items: List<JsonObject>): String {
        if (!resolves(server)) {
            bySession.remove(session)
            return ""
        }
        val token = "completion-${tokens.incrementAndGet()}"
        bySession[session] = Offered(token, server, items)
        return token
    }

    /** The documentation of item [index] of answer [token]; null when it has none, or the answer was replaced. */
    suspend fun resolve(session: String, token: String, index: Int): String? {
        val offered = bySession[session]?.takeIf { it.token == token }
        val item = offered?.items?.getOrNull(index) ?: return null
        val answer =
            try {
                withTimeoutOrNull(timeoutMs) { offered.server.request("completionItem/resolve", item) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LspConnectionClosedException) {
                null
            } catch (_: LspResponseException) {
                null
            }
        val documentation = (answer as? JsonObject)?.get("documentation")
        return contentsMarkdown(documentation)?.takeIf { it.isNotBlank() }
    }

    /** [session] ended. */
    fun release(session: String) {
        bySession.remove(session)
    }

    private fun resolves(server: ManagedServer): Boolean {
        val provider = server.capability("completionProvider") as? JsonObject
        val resolve = provider?.get("resolveProvider") as? JsonPrimitive
        return resolve?.booleanOrNull == true
    }
}
