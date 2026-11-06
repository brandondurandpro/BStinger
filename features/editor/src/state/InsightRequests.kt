package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.DocumentPosition
import java.io.IOException

/**
 * The requests of the code intelligence to the language servers. Each answer is null when there is no backend, when
 * the server does not mirror the document or when the link failed: the caller then shows "not ready".
 */
internal class InsightRequests(private val backend: () -> InsightBackend?) {
    /** Whether a language server indexes the project. */
    val indexing: Boolean get() = backend()?.indexing == true

    private suspend fun <T> ask(request: suspend (InsightBackend) -> T?): T? {
        val current = backend() ?: return null
        return try {
            request(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        }
    }

    /** Asks about [pos] of [document], as the server has it. */
    suspend fun <T> askAt(
        path: String,
        document: Document,
        pos: TextPos,
        request: suspend (CodeIntelligence, DocumentPosition) -> T?,
    ): T? =
        ask { current ->
            val version = current.versionOf(path, document)
            version?.let { request(current.intelligence, DocumentPosition(path, it, pos.line, pos.column)) }
        }

    /** Asks about the version of [document] the server has. */
    suspend fun <T> askVersion(path: String, document: Document, request: suspend (CodeIntelligence, Int) -> T?): T? =
        ask { current ->
            val version = current.versionOf(path, document)
            version?.let { request(current.intelligence, it) }
        }

    /** Asks the language servers something that is about no document. */
    suspend fun <T> askServers(request: suspend (CodeIntelligence) -> T?): T? =
        ask { current -> request(current.intelligence) }
}
