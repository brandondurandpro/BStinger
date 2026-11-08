package org.example.kotlineditor.lsp.documents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.TextChange

/** How a language server wants the edits: the whole text each time, or only what changed. */
enum class SyncKind {
    /** `didChange` carries the whole text. */
    Full,

    /** `didChange` carries the edits. */
    Incremental,
}

private const val MEGABYTE = 1024L * 1024

/** Pending incremental changes of one document past this size are replaced by its whole text. */
internal const val DEFAULT_MAX_CHANGE_BYTES = MEGABYTE

/** A server that has not read this much of what it was sent is stuck (see [DocumentOutbox]). */
internal const val DEFAULT_MAX_UNREAD_BYTES = 256 * MEGABYTE

// What a submission to the queue came to.
private sealed interface Submitted {
    data object Queued : Submitted

    // The outbox is closed, or gave up on its server.
    data object Refused : Submitted

    // The server was given more than it may leave unread: the queue was dropped.
    class Stuck(val flushes: List<Outgoing.Flush>, val unreadBytes: Long) : Submitted
}

/**
 * The document notifications of one language server process, sent in order by one coroutine: they are queued
 * under the lock of [OpenDocuments] (never blocking it on a slow pipe), and a request that must see them, such as
 * completion, waits for [flush] first.
 *
 * The changes of a document not written yet are merged into one `didChange` (see #153), so that a server that reads
 * slowly, or not at all, does not make the queue grow by the whole text at each keystroke: with [SyncKind.Full] it
 * keeps the latest text only, whose lines are shared with the [MirroredDocument] and joined when written; with
 * [SyncKind.Incremental] it keeps the edits in order, replaced by the whole text past [maxChangeBytes]. The order
 * LSP requires for a document holds: an open, a close or a save ends the merging, so its changes never jump over
 * them; a merged change may only be written earlier than it would have been, never later than a [flush] queued after
 * it.
 *
 * A server that is given more than [maxUnreadBytes] while one message is being written to it is stuck (alive, but
 * no longer reading its input): the queue is dropped and [onStuck] is told, so that the process gets restarted.
 * What is queued while nothing is being written does not count: a large document opened, or every document opened
 * again in a new process, is written at once to a server that reads.
 */
@Suppress("TooManyFunctions")
internal class DocumentOutbox(
    private val link: LspConnection,
    scope: CoroutineScope,
    private val kind: SyncKind,
    private val maxUnreadBytes: Long = DEFAULT_MAX_UNREAD_BYTES,
    private val maxChangeBytes: Long = DEFAULT_MAX_CHANGE_BYTES,
    private val onStuck: (unreadBytes: Long) -> Unit = {},
) {
    private val lock = Any()

    // Guarded by [lock], like everything below.
    private val queue = ArrayDeque<Outgoing>()

    // The queued didChange of each document that later changes still merge into.
    private val merging = HashMap<String, Outgoing.Changing>()
    private var queuedBytes = 0L

    // Whether a message is being written; what the server was given meanwhile, merged or queued.
    private var writing = false
    private var unreadBytes = 0L
    private var closed = false
    private var stuck = false

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) drain()
        }
    }

    fun didOpen(document: MirroredDocument) {
        val opening = Outgoing.Opening(document.uri, document.languageId, document.lspVersion, snapshotOf(document))
        submit { append(opening, endsMergeOf = document.uri) }
    }

    fun didChange(document: MirroredDocument, changes: List<TextChange>) {
        submit { merge(document, changes) }
    }

    fun didClose(document: MirroredDocument) =
        notification("textDocument/didClose", identifierOf(document), endsMergeOf = document.uri)

    fun didSave(document: MirroredDocument) =
        notification("textDocument/didSave", identifierOf(document), endsMergeOf = document.uri)

    /** Files changed on disk: their URI and FileChangeType (1 created, 2 changed, 3 deleted). */
    fun filesChanged(changes: List<Pair<String, Int>>) {
        val encoded =
            changes.map { (uri, type) ->
                buildJsonObject {
                    put("uri", uri)
                    put("type", type)
                }
            }
        notification("workspace/didChangeWatchedFiles", buildJsonObject { put("changes", JsonArray(encoded)) })
    }

    /** Files were created, renamed or deleted (`workspace/didRenameFiles`...): [params] from [fileOperationParams]. */
    fun filesOperated(operation: FileOperation, params: JsonObject) =
        notification("workspace/did${operation.lspName}Files", params)

    /** Returns once everything queued before has been written to the server (or dropped, if it is stuck). */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        val marker = Outgoing.Flush(done)
        val queued = submit { append(marker) }
        if (!queued) return
        try {
            done.await()
        } finally {
            // Cancelled while the server is slow: the marker would stay queued for nobody.
            if (!done.isCompleted) synchronized(lock) { queue.remove(marker) }
        }
    }

    /** Accepts nothing more; what is queued is still written, then the coroutine ends. */
    fun close() {
        synchronized(lock) { closed = true }
        wake.close()
    }

    private fun notification(method: String, params: JsonObject, endsMergeOf: String? = null) {
        submit { append(Outgoing.Notification(method, params), endsMergeOf) }
    }

    // Runs [update] under the lock, unless the outbox refuses: it queues something and returns the bytes it gave.
    private fun submit(update: () -> Long): Boolean {
        val outcome = synchronized(lock) { submitLocked(update) }
        when (outcome) {
            Submitted.Queued -> {
                wake.trySend(Unit)
            }

            Submitted.Refused -> {
                // closed, or replaced soon by the outbox of a new process
            }

            is Submitted.Stuck -> {
                outcome.flushes.forEach { it.done.complete(Unit) }
                onStuck(outcome.unreadBytes)
            }
        }
        return outcome != Submitted.Refused
    }

    // Under the lock: what [submit] comes to.
    private fun submitLocked(update: () -> Long): Submitted {
        val refused = closed || stuck
        if (refused) return Submitted.Refused
        val given = update()
        if (writing) unreadBytes += given
        return if (unreadBytes > maxUnreadBytes) giveUp() else Submitted.Queued
    }

    // Under the lock: queues [entry] last; the change of [endsMergeOf] queued before takes no more edits.
    private fun append(entry: Outgoing, endsMergeOf: String? = null): Long {
        endsMergeOf?.let(merging::remove)
        queue.addLast(entry)
        queuedBytes += entry.bytes
        return entry.bytes
    }

    // Under the lock: [changes], already applied to [document], join its queued didChange, or start one.
    private fun merge(document: MirroredDocument, changes: List<TextChange>): Long {
        val pending = merging[document.uri]
        val target = pending ?: Outgoing.Changing(document.uri)
        if (pending == null) {
            merging[document.uri] = target
            queue.addLast(target)
        }
        val before = target.bytes
        target.version = document.lspVersion
        val given: Long
        if (kind == SyncKind.Full || target.isWhole) {
            target.replaceText(snapshotOf(document))
            given = target.bytes
        } else {
            target.add(changes)
            given = changesSize(changes)
        }
        // Too many edits for one message: the whole text takes their place.
        if (target.bytes > maxChangeBytes && !target.isWhole) target.replaceText(snapshotOf(document))
        queuedBytes += target.bytes - before
        return given
    }

    // Under the lock: drops the queue of a server that does not read it any more.
    private fun giveUp(): Submitted.Stuck {
        stuck = true
        val flushes = queue.filterIsInstance<Outgoing.Flush>()
        queue.clear()
        merging.clear()
        queuedBytes = 0
        return Submitted.Stuck(flushes, unreadBytes)
    }

    private fun drain() {
        var next = take()
        while (next != null) {
            write(next)
            next = take()
        }
    }

    // The first entry, which takes no more changes once out of the queue.
    private fun take(): Outgoing? =
        synchronized(lock) {
            val first = queue.removeFirstOrNull()
            if (first is Outgoing.Changing && merging[first.uri] === first) merging.remove(first.uri)
            if (first != null) queuedBytes -= first.bytes
            writing = first is Outgoing.Message
            unreadBytes = 0
            first
        }

    private fun write(entry: Outgoing) {
        when (entry) {
            is Outgoing.Flush -> {
                entry.done.complete(Unit)
            }

            is Outgoing.Message -> {
                runCatching { link.notify(entry.method, entry.params()) }
                // The server read it (or is gone).
                synchronized(lock) { writing = false }
            }
        }
    }
}
