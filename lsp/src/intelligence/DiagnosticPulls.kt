package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.Diagnostic
import java.util.concurrent.ConcurrentHashMap

private const val DIAGNOSTIC_PROVIDER = "diagnosticProvider"

/**
 * The diagnostics a language server gives when asked (`textDocument/diagnostic`, LSP 3.17) rather than publishes:
 * pulled once typing pauses in a document, again on `workspace/diagnostic/refresh` and after a restart, with the
 * `resultId` of the previous report so that an unchanged one costs nothing.
 */
internal class DiagnosticPulls(
    private val scope: CoroutineScope,
    private val serversOf: (MirroredDocument) -> List<ManagedServer>,
    private val openDocuments: () -> List<MirroredDocument>,
    private val timeoutMs: Long,
    private val store: (server: String, document: MirroredDocument, diagnostics: List<Diagnostic>) -> Unit,
    private val delayMs: Long = PULL_DELAY_MS,
) {
    // The latest change of each document, by URI: a pull planned for an older one is dropped.
    private val stamps = ConcurrentHashMap<String, Long>()
    private val resultIds = ConcurrentHashMap<String, String>()

    /** [document] was opened or changed: its diagnostics are pulled once typing pauses. */
    fun changed(document: MirroredDocument) {
        val pulling = serversOf(document).filter { it.supports(DIAGNOSTIC_PROVIDER) == true }
        if (pulling.isEmpty()) return
        // A pull already asked is not cancelled (it would cancel its request): its answer is still the newest.
        val stamp = stamps.merge(document.uri, 1L, Long::plus)
        scope.launch {
            delay(delayMs)
            if (stamps[document.uri] == stamp) pulling.forEach { pull(it, document) }
        }
    }

    /** [server] asked for its diagnostics to be pulled again, or restarted: those of every open document are. */
    fun refresh(server: String) {
        resultIds.keys.removeIf { it.startsWith("$server ") }
        openDocuments().filter { document -> serversOf(document).any { it.id == server } }.forEach(::changed)
    }

    /** [document] is not open any more. */
    fun closed(document: MirroredDocument) {
        stamps.remove(document.uri)
        resultIds.keys.removeIf { it.endsWith(" ${document.uri}") }
    }

    private suspend fun pull(server: ManagedServer, document: MirroredDocument) {
        val key = "${server.id} ${document.uri}"
        val params =
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", document.uri) }
                resultIds[key]?.let { put("previousResultId", it) }
            }
        val report = reportOf(server, params) ?: return
        report.string("resultId")?.let { resultIds[key] = it }
        // `unchanged`: the previous report still holds.
        if (report.string("kind") == "full") store(server.id, document, diagnosticsOf(report, "items"))
    }

    private suspend fun reportOf(server: ManagedServer, params: JsonObject): JsonObject? =
        try {
            withTimeoutOrNull(timeoutMs) { server.request("textDocument/diagnostic", params) } as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LspConnectionClosedException) {
            null
        } catch (_: LspResponseException) {
            null
        }

    private companion object {
        const val PULL_DELAY_MS = 300L
    }
}
