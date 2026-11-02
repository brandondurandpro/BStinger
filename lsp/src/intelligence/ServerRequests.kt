package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.ManagedServer

/** A language server could not answer: none ready, or it went away. */
internal class NotReadyException : Exception()

/**
 * The requests of [CodeIntelligenceService] to the language server of a document, once the version the client means
 * arrived, with their timeout and the failures that make them not ready, unsupported or refused.
 */
internal class ServerRequests(
    private val documents: OpenDocuments,
    private val serversOf: (MirroredDocument) -> List<ManagedServer>,
    private val settings: IntelligenceSettings,
) {
    // Runs the block with the document once the session's version arrived, and a request to its language server
    // offering the capability: notReady when no server is ready (or it went away), unsupported when the ready one
    // does not offer it, refused when it answers with an error.
    @Suppress("LongParameterList")
    suspend fun <T> ask(
        session: String,
        path: String,
        version: Int,
        capability: String,
        notReady: T,
        unsupported: T,
        refused: (LspResponseException) -> T = { notReady },
        block: suspend (MirroredDocument, suspend (String, JsonObject) -> JsonElement?) -> T,
    ): T {
        val document = awaitDocument(session, path, version)
        val readyServers = document?.let { serversOf(it) }.orEmpty().filter { it.supports(capability) != null }
        val server = readyServers.firstOrNull { it.supports(capability) == true }
        return when {
            document == null || readyServers.isEmpty() -> notReady
            server == null -> unsupported
            else -> answered(server, document, notReady, refused, block)
        }
    }

    suspend fun <T> answered(
        server: ManagedServer,
        document: MirroredDocument,
        notReady: T,
        refused: (LspResponseException) -> T,
        block: suspend (MirroredDocument, suspend (String, JsonObject) -> JsonElement?) -> T,
    ): T = answeredBy(server, notReady, refused) { request -> block(document, request) }

    // What [block] finds asking [server]; [notReady] when it cannot answer in time, [refused] when it refuses.
    suspend fun <T> answeredBy(
        server: ManagedServer,
        notReady: T,
        refused: (LspResponseException) -> T = { notReady },
        block: suspend (suspend (String, JsonObject) -> JsonElement?) -> T,
    ): T {
        val request: suspend (String, JsonObject) -> JsonElement? = { method, params ->
            val answer = server.request(method, params) ?: throw NotReadyException()
            answer.takeIf { it != JsonNull }
        }
        return try {
            withTimeoutOrNull(settings.timeoutMs) { block(request) } ?: notReady
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NotReadyException) {
            notReady
        } catch (_: LspConnectionClosedException) {
            notReady
        } catch (failure: LspResponseException) {
            refused(failure)
        }
    }

    suspend fun awaitDocument(session: String, path: String, version: Int): MirroredDocument? {
        val document = documents.get(path) ?: return null
        val arrived =
            withTimeoutOrNull(settings.timeoutMs) {
                document.clientVersion.firstOrNull { it.session == session && it.version >= version }
            }
        return document.takeIf { arrived != null }
    }
}
