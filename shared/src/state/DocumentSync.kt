package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.TextChange
import java.io.IOException

private const val RETRY_MS = 1_000L

/** One document whose text the server mirrors for its language server. */
private class SyncedDocument(val project: String, val path: String, val document: Document) {
    // Version of the last open or change sent.
    var version = 0
    val pending = ArrayList<TextChange>()
    var needsOpen = true
    val wake = Channel<Unit>(Channel.CONFLATED)
    var job: Job? = null

    /** The version that has every edit made so far, once what is pending is sent. */
    val latest: Int get() = if (pending.isEmpty() && !needsOpen) version else version + 1
}

/**
 * Sends the text of the open documents that have a language server to the server (see #96): the whole text once,
 * then each edit as it is typed. Edits typed while a request travels go together in the next one, so a slow link
 * sends fewer, bigger changes. When the server refuses a change (it restarted and lost the document, a change was
 * lost), or after a reconnection, the whole text is sent again. Runs on the UI thread, like the edits.
 */
internal class DocumentSync(private val servers: LanguageServers, private val scope: CoroutineScope) {
    private val synced = HashMap<String, SyncedDocument>()

    /** The documents open in [project] are [documents], by path: new ones are opened, the others closed. */
    fun track(project: String?, documents: Map<String, Document>) {
        val wanted = if (project == null) emptyMap() else documents.filterKeys { Languages.languageOf(it) != null }
        for ((path, entry) in synced.entries.toList()) {
            if (wanted[path] !== entry.document || entry.project != project) {
                synced.remove(path)
                stop(entry)
            }
        }
        for ((path, document) in wanted) {
            if (path !in synced) synced[path] = start(SyncedDocument(project!!, path, document))
        }
    }

    /**
     * The version of [path] that has every edit made so far, to ask the server something about it; null when it is
     * not synced (no language server for it).
     */
    fun versionOf(path: String, document: Document): Int? = synced[path]?.takeIf { it.document === document }?.latest

    /** The server may have lost the documents (it restarted): they are sent again. */
    fun resend() {
        synced.values.forEach {
            it.needsOpen = true
            it.wake.trySend(Unit)
        }
    }

    /** Stops following every document, without telling the server (another one is used now). */
    fun close() {
        synced.values.forEach { it.document.editListener = null }
        synced.clear()
    }

    private fun start(entry: SyncedDocument): SyncedDocument {
        entry.document.editListener = { change ->
            entry.pending += change
            entry.wake.trySend(Unit)
        }
        entry.wake.trySend(Unit)
        entry.job = scope.launch { for (signal in entry.wake) send(entry) }
        return entry
    }

    private fun stop(entry: SyncedDocument) {
        if (entry.document.editListener != null) entry.document.editListener = null
        entry.job?.cancel()
        scope.launch {
            try {
                servers.closeDocument(entry.path)
            } catch (_: IOException) {
                // The server forgets the documents of a session that ends: nothing is left behind.
            }
        }
    }

    // Sends what is pending, the whole text when needed, until nothing is.
    private suspend fun send(entry: SyncedDocument) {
        while (entry.needsOpen || entry.pending.isNotEmpty()) {
            val opening = entry.needsOpen
            val changes = entry.pending.toList()
            val text = if (opening) entry.document.lines.joinToString("\n") else ""
            entry.pending.clear()
            entry.needsOpen = false
            entry.version++
            try {
                if (opening) {
                    servers.openDocument(entry.project, entry.path, entry.version, text)
                } else {
                    servers.changeDocument(entry.path, entry.version, changes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                entry.needsOpen = true
                delay(RETRY_MS)
            }
        }
    }
}
