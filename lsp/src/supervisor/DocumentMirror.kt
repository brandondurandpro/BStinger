package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.lsp.documents.DocumentOutbox
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.TextChange

/**
 * The open documents of [language] under [root], as a server's process has them: sent through an outbox once the
 * process answered `initialize`, dropped when it stops. [documents] null: no document is ever sent (tests).
 */
internal class DocumentMirror(
    private val documents: OpenDocuments?,
    private val language: String,
    private val root: String,
) {
    /** The outbox of the ready process; null otherwise. */
    @Volatile var outbox: DocumentOutbox? = null
        private set

    /**
     * Opens every document in [box], the outbox of a process that just answered `initialize`. Under the documents'
     * lock: an edit arriving meanwhile is either replayed here or sent after.
     */
    fun open(box: DocumentOutbox) {
        val open = { mirrored: List<MirroredDocument> ->
            outbox = box
            mirrored.forEach(box::didOpen)
        }
        documents?.withDocuments(language, root, open) ?: open(emptyList())
    }

    /** The process stops: nothing more is sent to it. */
    fun close() {
        val close = {
            outbox?.close()
            outbox = null
        }
        documents?.locked(close) ?: close()
    }

    fun didOpen(document: MirroredDocument) {
        outbox?.didOpen(document)
    }

    fun didChange(document: MirroredDocument, changes: List<TextChange>) {
        outbox?.didChange(document, changes)
    }

    fun didClose(document: MirroredDocument) {
        outbox?.didClose(document)
    }

    fun didSave(document: MirroredDocument) {
        outbox?.didSave(document)
    }

    fun filesChanged(changes: List<Pair<String, Int>>) {
        outbox?.filesChanged(changes)
    }

    fun filesOperated(operation: FileOperation, params: JsonObject) {
        outbox?.filesOperated(operation, params)
    }
}
