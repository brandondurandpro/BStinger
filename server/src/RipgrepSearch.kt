package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.IOException
import kotlin.time.Duration

/**
 * [ProjectSearch] whose Find in Files runs ripgrep, like VS Code: native, parallel, no index, and it honours
 * `.gitignore`. The file lists too (`rg --files`, for Go to File). The other queries, and every query whenever
 * ripgrep is missing or fails, use [fallback].
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

    // The file lists (rg --files) and the streamed searches once an executable is found; each asks this class
    // for it, so that one location serves every query.
    private val files =
        RipgrepFiles(
            maxFileSize,
            timeout,
            onStart,
            binary = { ripgrep() },
        )
    private val streams = RipgrepStream(fallback, maxFileSize, onStart, binary = { ripgrep() })

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

    override suspend fun findInFiles(
        root: FileEntry,
        query: String,
        ignoreCase: Boolean,
        limit: Int,
    ): List<TextMatch> = findInFiles(root, FindOptions(text = query, ignoreCase = ignoreCase), limit)

    override suspend fun findInFiles(root: FileEntry, options: FindOptions, limit: Int): List<TextMatch> {
        val executable =
            if (options.text.isEmpty() || limit <= 0) null else ripgrep()
        return executable?.let { search(it, root, options, limit) } ?: fallback.findInFiles(root, options, limit)
    }

    override suspend fun walkFiles(root: FileEntry, limit: Int): List<FileEntry> {
        val executable = if (limit <= 0) null else ripgrep()
        return executable?.let { files.entries(root, limit) } ?: fallback.walkFiles(root, limit)
    }

    /** Same as [searchBatches] of [RipgrepStream]: keeps the client's reads below the wiring. */
    fun searchBatches(root: FileEntry, options: FindOptions, limit: Int): Flow<SearchBatch> =
        streams.searchBatches(root, options, limit)

    // Null when ripgrep failed: the built-in search takes over.
    private suspend fun search(
        executable: RipgrepBinary,
        root: FileEntry,
        options: FindOptions,
        limit: Int,
    ): List<TextMatch>? =
        try {
            val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
            val hits = ArrayList<TextMatch>()
            // On timeout, the lines found so far are the answer.
            withTimeoutOrNull(timeout) {
                runner.matches(queryOf(root, options)).take(limit).collect { hits += it.toTextMatch(MAX_LINE_PREVIEW) }
            }
            // ripgrep searches files in parallel: sorting keeps the order stable from one search to the next.
            hits.sortedWith(compareBy<TextMatch> { it.entry.path }.thenBy { it.line })
        } catch (_: IOException) {
            null
        }

    private companion object {
        /** Same preview length as the built-in search. */
        const val MAX_LINE_PREVIEW = 500
    }
}
