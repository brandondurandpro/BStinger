package org.example.kotlineditor.lsp.completion

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
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
            val answered =
                withTimeoutOrNull(timeoutMs) {
                    serversOf(document).firstNotNullOfOrNull { server ->
                        server.request("textDocument/completion", params)?.let { server to it }
                    }
                }
            answered?.let { (server, answer) ->
                val (result, items) = CompletionAnswers.translateWithItems(answer, text, column)
                result.copy(token = offered.offer(session, server, items))
            } ?: CompletionResult.NotReady
        } catch (_: LspConnectionClosedException) {
            CompletionResult.NotReady
        } catch (_: LspResponseException) {
            // The server refused (content modified, request failed): nothing to propose this time.
            CompletionResult(column, emptyList(), incomplete = true, ready = true)
        }
    }
}
