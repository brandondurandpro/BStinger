package org.example.kotlineditor.lsp.documents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.model.TextChange

/** How a language server wants the edits: the whole text each time, or only what changed. */
enum class SyncKind {
    /** `didChange` carries the whole text. */
    Full,

    /** `didChange` carries the edits. */
    Incremental,
}

private sealed interface Outgoing {
    class Notification(val method: String, val params: JsonObject) : Outgoing

    class Flush(val done: CompletableDeferred<Unit>) : Outgoing
}

/**
 * The document notifications of one language server process, sent in order by one coroutine: they are queued
 * under the lock of [OpenDocuments] (never blocking it on a slow pipe), and a request that must see them, such as
 * completion, waits for [flush] first.
 */
internal class DocumentOutbox(private val link: LspConnection, scope: CoroutineScope, private val kind: SyncKind) {
    private val queue = Channel<Outgoing>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (message in queue) {
                when (message) {
                    is Outgoing.Notification -> runCatching { link.notify(message.method, message.params) }
                    is Outgoing.Flush -> message.done.complete(Unit)
                }
            }
        }
    }

    fun didOpen(document: MirroredDocument) =
        send(
            "textDocument/didOpen",
            buildJsonObject {
                putJsonObject(TEXT_DOCUMENT) {
                    put(URI, document.uri)
                    put("languageId", document.language)
                    put(VERSION, document.lspVersion)
                    put(TEXT, document.text())
                }
            },
        )

    fun didChange(document: MirroredDocument, changes: List<TextChange>) {
        val contentChanges =
            when (kind) {
                SyncKind.Full -> listOf(buildJsonObject { put(TEXT, document.text()) })
                SyncKind.Incremental -> changes.map(::contentChange)
            }
        send(
            "textDocument/didChange",
            buildJsonObject {
                putJsonObject(TEXT_DOCUMENT) {
                    put(URI, document.uri)
                    put(VERSION, document.lspVersion)
                }
                put("contentChanges", JsonArray(contentChanges))
            },
        )
    }

    fun didClose(document: MirroredDocument) =
        send("textDocument/didClose", buildJsonObject { putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) } })

    /** Returns once everything queued before has been written to the server. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Outgoing.Flush(done)).isSuccess) done.await()
    }

    fun close() {
        queue.close()
    }

    private fun send(method: String, params: JsonObject) {
        queue.trySend(Outgoing.Notification(method, params))
    }

    private fun contentChange(change: TextChange) =
        buildJsonObject {
            putJsonObject("range") {
                putJsonObject("start") {
                    put(LINE, change.startLine)
                    put(CHARACTER, change.startColumn)
                }
                putJsonObject("end") {
                    put(LINE, change.endLine)
                    put(CHARACTER, change.endColumn)
                }
            }
            put(TEXT, change.text)
        }

    private companion object {
        const val TEXT_DOCUMENT = "textDocument"
        const val URI = "uri"
        const val VERSION = "version"
        const val TEXT = "text"
        const val LINE = "line"
        const val CHARACTER = "character"
    }
}
