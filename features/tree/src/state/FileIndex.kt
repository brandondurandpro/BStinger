package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import org.example.kotlineditor.telemetry.sizeOf
import java.io.IOException

@Immutable
data class FileMatch(val entry: FileEntry, val relativePath: String, val score: Int, val matchedIndices: List<Int>)

@Immutable
data class SymbolMatch(
    val entry: FileEntry,
    val relativePath: String,
    val declaration: Declaration,
    val score: Int,
    val matchedIndices: List<Int>,
)

@Immutable
data class SearchHit(
    val entry: FileEntry,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val lineText: String,
)

private const val DEFAULT_RESULTS = 50
private const val DEFAULT_TEXT_HITS = 1000
private val BEST_FILE_FIRST =
    compareByDescending<FileMatch> {
        it.score
    }.thenBy { it.entry.name.length }.thenBy { it.relativePath }
private val BEST_SYMBOL_FIRST = compareByDescending<SymbolMatch> { it.score }.thenBy { it.declaration.name.length }

/** The project files and symbols (computed by the server), for Go to File, Go to Symbol and Find in Files. */
@Stable
class FileIndex(private val root: FileEntry, private val search: ProjectSearch, private val scope: CoroutineScope) {
    var files: List<FileEntry>? by mutableStateOf(null)
        private set

    private var job: Job? = null
    private var symbols: List<Pair<FileEntry, Declaration>>? = null

    fun ensureLoaded() {
        if (files == null && job?.isActive != true) {
            job =
                scope.launch {
                    val walked =
                        ClientTelemetry.measureToFrame(
                            Operation.TreeIndex,
                            attributesOf = { walked: List<FileEntry> -> sizeOf(walked.size) },
                        ) {
                            try {
                                search.walkFiles(root)
                            } catch (expected: IOException) {
                                emptyList() // server unreachable: an empty index, rebuilt on the next invalidation
                            }
                        }
                    files = walked
                }
        }
    }

    fun invalidate() {
        job?.cancel()
        job = null
        files = null
        symbols = null
    }

    /** Declarations of every Kotlin/Java file, computed by the server and cached until invalidated. */
    suspend fun allSymbols(): List<Pair<FileEntry, Declaration>> =
        symbols ?: search.symbols(root).map { it.entry to it.declaration }.also { symbols = it }

    suspend fun searchSymbols(query: String, limit: Int = DEFAULT_RESULTS): List<SymbolMatch> {
        val q = query.trim()
        val all = if (q.isEmpty()) emptyList() else allSymbols()
        return withContext(Dispatchers.Default) {
            val matches =
                all.mapNotNull { (entry, declaration) ->
                    fuzzyMatch(
                        q,
                        declaration.name,
                    )?.let { (score, indices) -> SymbolMatch(entry, relativePath(entry), declaration, score, indices) }
                }
            matches.sortedWith(BEST_SYMBOL_FIRST).take(limit)
        }
    }

    /** [declaration] of [entry] as Go to Symbol lists it for [query], null when its name does not match. */
    fun symbolMatch(entry: FileEntry, declaration: Declaration, query: String): SymbolMatch? {
        val matched = fuzzyMatch(query.trim(), declaration.name) ?: return null
        val (score, indices) = matched
        return SymbolMatch(entry, relativePath(entry), declaration, score, indices)
    }

    fun relativePath(entry: FileEntry): String = relativeToRoot(entry.path, root.path)

    /** Go to File: fuzzy matching on the file names (the index is local, results are instant). */
    suspend fun searchFiles(query: String, limit: Int = DEFAULT_RESULTS): List<FileMatch> {
        val all = files.orEmpty()
        val q = query.trim()
        return withContext(Dispatchers.Default) {
            val matches =
                if (q.isEmpty()) {
                    all.take(limit).map { FileMatch(it, relativePath(it), 0, emptyList()) }
                } else {
                    all.mapNotNull { entry ->
                        fuzzyMatch(
                            q,
                            entry.name,
                        )?.let { (score, indices) -> FileMatch(entry, relativePath(entry), score, indices) }
                    }
                }
            matches.sortedWith(BEST_FILE_FIRST).take(limit)
        }
    }

    /** Full-text search, run by the server next to the files: only matching lines cross the network. */
    suspend fun findInFiles(
        query: String,
        ignoreCase: Boolean = true,
        limit: Int = DEFAULT_TEXT_HITS,
    ): List<SearchHit> =
        if (query.isEmpty()) {
            emptyList()
        } else {
            search.findInFiles(root, query, ignoreCase, limit).map { hit ->
                SearchHit(hit.entry, relativePath(hit.entry), hit.line, hit.column, hit.lineText)
            }
        }

    companion object {
        /** See [FuzzyMatch]. */
        fun fuzzyMatch(query: String, name: String): Pair<Int, List<Int>>? = FuzzyMatch.match(query, name)

        /** [matches] best first, at most [limit]. */
        fun rankSymbols(matches: List<SymbolMatch>, limit: Int = DEFAULT_RESULTS): List<SymbolMatch> =
            matches.sortedWith(BEST_SYMBOL_FIRST).take(limit)
    }
}
