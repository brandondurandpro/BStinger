package org.example.kotlineditor.lsp.completion

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.CompletionResult

/**
 * Completion in the open documents: asked to their language server once the edits it follows arrived, then
 * translated and offered to the session that asked (see [OfferedCompletions]).
 */
internal class DocumentCompletions(
    private val documents: OpenDocuments,
    private val timeoutMs: Long,
    private val serversOf: (MirroredDocument) -> List<ManagedServer>,
) {
    private val offered = OfferedCompletions(timeoutMs)

    /** Completion at [line], [column] of [path], once [session]'s [version] of it arrived. */
    suspend fun complete(session: String, path: String, version: Int, line: Int, column: Int): CompletionResult {
        val document = documents.get(path)
        val hasValidClientVersion =
            document != null &&
                run {
                    val matchingVersion =
                        withTimeoutOrNull(timeoutMs) {
                            document.clientVersion.firstOrNull { it.session == session && it.version >= version }
                        }
                    matchingVersion != null
                }
        return if (document != null && hasValidClientVersion) {
            ask(session, document, line, column)
        } else {
            CompletionResult.NotReady
        }
    }

    /** The documentation of item [index] of [session]'s completion answer [token]. */
    suspend fun resolve(session: String, token: String, index: Int): String? = offered.resolve(session, token, index)

    /** [session] ended: its answers are forgotten. */
    fun release(session: String) = offered.release(session)

    private suspend fun ask(session: String, document: MirroredDocument, line: Int, column: Int): CompletionResult {
        val text = documents.locked { document.line(line) }
        val params =
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", document.uri) }
                putJsonObject("position") {
                    put("line", line)
                    put("character", column)
                }
            }
        return try {
            // While another server takes over the language, the old one, stopping, is not ready any more.
            val answers = withTimeoutOrNull(timeoutMs) { askAll(serversOf(document), params) }
            if (answers == null || answers.servers.isEmpty()) {
                if (answers?.refused == true) refused(column) else CompletionResult.NotReady
            } else {
                val merged = merge(answers.answers)
                val (result, items) = CompletionAnswers.translateWithItems(merged, text, column)
                result.copy(token = offered.offer(session, answers.servers, items))
            }
        } catch (_: LspConnectionClosedException) {
            CompletionResult.NotReady
        } catch (_: LspResponseException) {
            refused(column)
        }
    }

    // The server refused (content modified, request failed): nothing to propose this time.
    private fun refused(column: Int) = CompletionResult(column, emptyList(), incomplete = true, ready = true)

    // What each server of the document proposes, asked together: the server of its language and those of the
    // companion languages (Tailwind CSS in a class attribute, Vue in a template) each know part of the answer.
    private suspend fun askAll(servers: List<ManagedServer>, params: JsonObject): Answers =
        coroutineScope {
            val asked = servers.map { server -> async { askOne(server, params) } }.awaitAll()
            val answered = asked.mapNotNull { it?.getOrNull() }
            Answers(
                answered.map { it.first },
                answered.map { it.second },
                refused = asked.any { it?.isFailure == true },
            )
        }

    // The server and its answer; a failure when it refused; null when it is not ready or closed meanwhile.
    private suspend fun askOne(server: ManagedServer, params: JsonObject): Result<Pair<ManagedServer, JsonElement>>? =
        try {
            server.request("textDocument/completion", params)?.let { Result.success(server to it) }
        } catch (failure: LspResponseException) {
            Result.failure(failure)
        } catch (_: LspConnectionClosedException) {
            null
        }

    // One completion list: the items of every answer, each marked with the server it came from (by its position).
    private fun merge(answers: List<JsonElement>): JsonObject {
        var incomplete = false
        val items = mutableListOf<JsonElement>()
        answers.forEachIndexed { index, answer ->
            val list = answer as? JsonObject
            incomplete = incomplete || (list?.get("isIncomplete") as? JsonPrimitive)?.booleanOrNull == true
            val proposed = (list?.get("items") ?: answer) as? JsonArray
            val marker = JsonPrimitive(index)
            proposed?.filterIsInstance<JsonObject>()?.forEach {
                items += JsonObject(it + (OfferedCompletions.SERVER to marker))
            }
        }
        return JsonObject(mapOf("isIncomplete" to JsonPrimitive(incomplete), "items" to JsonArray(items)))
    }

    private class Answers(val servers: List<ManagedServer>, val answers: List<JsonElement>, val refused: Boolean)
}
