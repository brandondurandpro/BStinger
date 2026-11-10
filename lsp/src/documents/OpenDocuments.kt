package org.example.kotlineditor.lsp.documents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.TextChange
import java.io.IOException
import java.nio.file.Paths

/** A change refused because the server does not have the version it applies to: the client opens the document again. */
class OutOfSyncException(message: String) : IOException(message)

/** Version of a document as one client sent it. */
data class ClientVersion(
    /** The session that sent it. */
    val session: String,
    /** Its version number. */
    val version: Int,
)

/**
 * The text of a document open in the IDE, as the language servers see it (see #96): its lines, separated by `\n`,
 * and the version the last client edit made. Changed only under the lock of [OpenDocuments].
 */
class MirroredDocument internal constructor(
    /** Path on the server machine, as the client names it. */
    val path: String,
    /** Language of the server that handles it (`php`, `typescript`). */
    val language: String,
    /** Root of the language server that handles it. */
    val root: String,
) {
    /** What the servers are told it is (`typescriptreact` for a `.tsx`): [language] for the languages with one name. */
    val languageId: String = Languages.identifierOf(path) ?: language

    /** `file:` URI given to the language servers. */
    val uri: String = Paths.get(path).toUri().toString()

    internal val normalizedPath = Paths.get(path).toAbsolutePath().normalize()

    internal val lines = ArrayList<String>()
    private val clientVersions = MutableStateFlow(ClientVersion("", 0))

    /** Sessions that have it open. */
    internal val openers = HashSet<String>()

    /** Version given to the language servers: increases with every edit and every reopening. */
    var lspVersion = 0
        internal set

    /** The session that sent the last text, and the version it has. */
    val clientVersion: StateFlow<ClientVersion> get() = clientVersions

    /** The whole text. */
    fun text(): String = lines.joinToString("\n")

    /** Line [index], empty past the end. */
    fun line(index: Int): String = lines.getOrElse(index) { "" }

    internal fun reset(session: String, version: Int, text: String) {
        lines.clear()
        lines.addAll(text.split('\n'))
        lspVersion++
        clientVersions.value = ClientVersion(session, version)
    }

    internal fun apply(session: String, version: Int, changes: List<TextChange>) {
        val current = clientVersions.value
        if (current.session != session || current.version != version - 1) {
            throw OutOfSyncException("$path is at version ${current.version}, not ${version - 1}")
        }
        // Checked before any change is applied, so that a refused batch leaves the text untouched.
        val copy = ArrayList(lines)
        changes.forEach { applyTo(copy, it) }
        lines.clear()
        lines.addAll(copy)
        lspVersion++
        clientVersions.value = ClientVersion(session, version)
    }

    private fun applyTo(target: MutableList<String>, change: TextChange) {
        val startLine = change.startLine
        val startColumn = change.startColumn
        val endLine = change.endLine
        val endColumn = change.endColumn
        val linesExist = startLine in target.indices && endLine in startLine..target.lastIndex
        if (!linesExist) throw outside(change)
        val startExists = startColumn in 0..target[startLine].length
        val endExists = endColumn in 0..target[endLine].length
        val ordered = startLine < endLine || startColumn <= endColumn
        val columnsExist = startExists && endExists
        if (!columnsExist || !ordered) throw outside(change)
        val prefix = target[startLine].substring(0, startColumn)
        val suffix = target[endLine].substring(endColumn)
        val parts = change.text.split('\n')
        val replacement =
            parts.mapIndexed { index, part ->
                val head = if (index == 0) prefix else ""
                val tail = if (index == parts.lastIndex) suffix else ""
                head + part + tail
            }
        if (startLine == endLine && replacement.size == 1) {
            target[startLine] = replacement[0]
        } else {
            target.subList(startLine, endLine + 1).clear()
            target.addAll(startLine, replacement)
        }
    }

    private fun outside(change: TextChange) = OutOfSyncException("Edit $change outside of $path")
}

/**
 * The documents the IDE sessions have open, mirrored on the server for the language servers: what they are sent
 * when they start (or restart), and what completion reads. One mirror per path: two sessions editing the same file
 * take turns, the last one that opened it owns it, and the other one's next change is refused, so it opens the file
 * again. Every change happens under one lock, which the language servers also take to replay the documents, so
 * that a change is never lost between a replay and the next edit.
 */
class OpenDocuments {
    private val lock = Any()
    private val documents = HashMap<String, MirroredDocument>()

    /**
     * Opens [path] for [session], or replaces its text. [onOpened] runs under the lock with the document, so that
     * the language servers receive it in order with the changes. Null when no language server handles it.
     */
    fun open(
        session: String,
        path: String,
        version: Int,
        text: String,
        place: (String) -> Pair<String, String>?,
        onOpened: (MirroredDocument, reopened: Boolean) -> Unit,
    ): MirroredDocument? =
        synchronized(lock) {
            val existing = documents[path]
            val placement = if (existing == null) place(path) else null
            val created = placement?.let { MirroredDocument(path, language = it.first, root = it.second) }
            val document = existing ?: created
            document?.also {
                documents[path] = it
                it.openers += session
                it.reset(session, version, text)
                onOpened(it, existing != null)
            }
        }

    /** Applies [changes] of [session] to [path]; [onChanged] runs under the lock. */
    fun change(
        session: String,
        path: String,
        version: Int,
        changes: List<TextChange>,
        onChanged: (MirroredDocument) -> Unit,
    ) {
        synchronized(lock) {
            val document = documents[path] ?: throw OutOfSyncException("$path is not open")
            document.apply(session, version, changes)
            onChanged(document)
        }
    }

    /** [session] closed [path]; [onClosed] runs under the lock when nobody has it open any more. */
    fun close(session: String, path: String, onClosed: (MirroredDocument) -> Unit) {
        synchronized(lock) {
            val document = documents[path] ?: return
            document.openers -= session
            if (document.openers.isEmpty()) {
                documents.remove(path)
                onClosed(document)
            }
        }
    }

    /** [session] ended: closes what it had open. */
    fun release(session: String, onClosed: (MirroredDocument) -> Unit) {
        synchronized(lock) {
            documents.values
                .filter { session in it.openers }
                .map { it.path }
                .forEach { close(session, it, onClosed) }
        }
    }

    /** The document whose URI is [uri], null when not open. */
    fun byUri(uri: String): MirroredDocument? {
        // Servers may encode a URI otherwise (`%2A` for `*`): compared as paths when the text differs.
        val path = runCatching { Paths.get(java.net.URI(uri)).toAbsolutePath().normalize() }.getOrNull()
        return synchronized(lock) {
            documents.values.firstOrNull { it.uri == uri } ?: documents.values.firstOrNull { it.normalizedPath == path }
        }
    }

    /** Every open document. */
    fun all(): List<MirroredDocument> = synchronized(lock) { documents.values.toList() }

    /** The documents [session] has open. */
    fun openedBy(session: String): List<MirroredDocument> =
        synchronized(lock) { documents.values.filter { session in it.openers }.sortedBy { it.path } }

    /** The document at [path], null when not open. */
    fun get(path: String): MirroredDocument? = synchronized(lock) { documents[path] }

    /** Runs [block] under the lock with the documents of [language] under [root]. */
    fun <T> withDocuments(language: String, root: String, block: (List<MirroredDocument>) -> T): T =
        synchronized(lock) { block(documents.values.filter { isServedBy(it, language, root) }) }

    // Of the language and under the root of the server, or of a language it serves beside the one of its own server.
    private fun isServedBy(document: MirroredDocument, language: String, root: String): Boolean {
        if (document.language == language) return document.root == root
        val prefix = root.trimEnd('/', '\\')
        val path = document.path
        val inside = path == prefix || path.startsWith("$prefix/") || path.startsWith("$prefix\\")
        return inside && language in Languages.companionsOf(document.path)
    }

    /** Runs [block] under the lock: reads of a document's lines that must not see half an edit. */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)
}
