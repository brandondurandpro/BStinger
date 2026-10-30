package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepMatch
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepQuery
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.IOException
import java.nio.file.Paths
import kotlin.time.Duration

/**
 * [ProjectSearch] whose Find in Files runs ripgrep, like VS Code: native, parallel, no index, and it honours
 * `.gitignore`. The other queries, and Find in Files whenever ripgrep is missing or fails, use [fallback].
 */
class RipgrepSearch internal constructor(
    private val fallback: LocalSearch,
    private val provisioner: RipgrepProvisioner,
    private val maxFileSize: Long,
    private val timeout: Duration,
    private val onStart: (ProcessHandle) -> Unit,
) : ProjectSearch by fallback {
    private val lookup = Mutex()
    private var located = false
    private var binary: RipgrepBinary? = null

    /** Search with ripgrep found as [settings] say; files larger than [maxFileSize] are not searched. */
    constructor(
        fallback: LocalSearch,
        settings: RipgrepSettings = RipgrepSettings(),
        maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ) : this(fallback, RipgrepProvisioner(settings), maxFileSize, settings.timeout, {})

    /**
     * The ripgrep executable in use, looked up (and downloaded if needed) on the first call only; null when the
     * built-in search is used instead.
     */
    suspend fun ripgrep(): RipgrepBinary? =
        lookup.withLock {
            if (!located) {
                binary = withContext(Dispatchers.IO) { provisioner.locate() }
                located = true
            }
            binary
        }

    override suspend fun findInFiles(root: FileEntry, query: String, ignoreCase: Boolean, limit: Int): List<TextMatch> {
        val executable = if (query.isEmpty() || limit <= 0) null else ripgrep()
        return executable?.let { search(it, root, query, ignoreCase, limit) }
            ?: fallback.findInFiles(root, query, ignoreCase, limit)
    }

    // Null when ripgrep failed: the built-in search takes over.
    private suspend fun search(
        executable: RipgrepBinary,
        root: FileEntry,
        query: String,
        ignoreCase: Boolean,
        limit: Int,
    ): List<TextMatch>? =
        try {
            val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
            val matches = runner.matches(RipgrepQuery(Paths.get(root.path), query, ignoreCase))
            val hits = ArrayList<TextMatch>()
            // On timeout, the lines found so far are the answer.
            withTimeoutOrNull(timeout) { matches.take(limit).collect { hits += it.toTextMatch() } }
            // ripgrep searches files in parallel: sorting keeps the order stable from one search to the next.
            hits.sortedWith(compareBy<TextMatch> { it.entry.path }.thenBy { it.line })
        } catch (_: IOException) {
            null
        }

    private fun RipgrepMatch.toTextMatch(): TextMatch {
        val file = Paths.get(path)
        val entry = FileEntry(file.toString(), file.fileName?.toString() ?: path, false)
        return TextMatch(entry, line, ranges.firstOrNull()?.first ?: 0, text.take(MAX_LINE_PREVIEW))
    }

    private companion object {
        /** Same preview length as the built-in search. */
        const val MAX_LINE_PREVIEW = 500
    }
}
