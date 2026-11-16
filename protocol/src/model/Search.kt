package org.example.kotlineditor.model

import kotlinx.coroutines.flow.Flow
import org.example.kotlineditor.syntax.Declaration

/** A hit of Find in Files. */
data class TextMatch(
    /** File containing the match. */
    val entry: FileEntry,
    /** Zero-based line of the match. */
    val line: Int,
    /** Zero-based column of the match. */
    val column: Int,
    /** Text of the matching line. */
    val lineText: String,
)

/** The Find in Files options: what to search, where and in which files.
 *
 * The basic search (`text`, `ignoreCase`) is what every version of the protocol sends; the other fields are
 * optional and default to the plain literal search, so an older client that omits them still works.
 */
data class FindOptions(
    /** Text searched: literally, unless [regex]. */
    val text: String,
    /** Whether letter case is ignored. */
    val ignoreCase: Boolean = false,
    /** Whether only matches of whole words (letters, digits, `_`) count. */
    val wholeWord: Boolean = false,
    /** Whether [text] is a regular expression instead of literal text. */
    val regex: Boolean = false,
    /** Only files whose name matches this glob (`*.kt`), or null for all of them. */
    val filePattern: String? = null,
    /** Directories searched, relative to the root of the project (`src`), or none for its whole root. */
    val scopes: List<String> = emptyList(),
    /** Whether ignored files (Git-ignored, `.git`, build output) are searched too. */
    val includeIgnored: Boolean = false,
)

/** Which engine ran a Find in Files: the built-in one takes over whenever ripgrep cannot. */
enum class SearchEngine {
    /** The native ripgrep binary, downloaded on demand, that honours the ignores of the repository. */
    Ripgrep,

    /** The built-in engine of the server, the fallback whenever ripgrep cannot run at all. */
    BuiltIn,
}

/**
 * One batch of the results of a streamed Find in Files: the matches found so far, numbered so a client that
 * missed batches after a reconnection can ask the server for what follows [seq].
 */
data class SearchBatch(
    /** Sequence number of the batch, from 1. */
    val seq: Long,
    /** Matches found since the previous batch. */
    val hits: List<TextMatch>,
    /** Whether this batch is the last one of the search. */
    val done: Boolean,
    /** Total number of matches found so far (final once [done]). */
    val total: Int,
    /** Engine that ran the search, known once [done] (a failure has none). */
    val engine: SearchEngine?,
    /** Why the search failed, when it could not complete (an invalid regex...): the popup shows it. */
    val error: String? = null,
)

/**
 * A Find in Files running as a stream on the server: [batches] delivers its results while it runs, and [id]
 * resumes it after a reconnection.
 */
class SearchStream(
    /** Id of the stream: the id of the request that started it. */
    val id: Long,
    /** The batches of the search, in order; the stream closes once the batch that is [SearchBatch.done] arrived. */
    val batches: Flow<SearchBatch>,
)

/** A declaration found in a project file (Go to Symbol, Go to Declaration). */
data class FileSymbol(
    /** File declaring the symbol. */
    val entry: FileEntry,
    /** The declaration itself. */
    val declaration: Declaration,
)

/** Files of the projects. Implemented by the server, used by the client through the network. */
interface ProjectSearch {
    /** Every regular file under [root], skipping VCS and build output directories. */
    suspend fun walkFiles(root: FileEntry, limit: Int = DEFAULT_WALK_LIMIT): List<FileEntry>

    /** The directories and common extensions of the files under [root], what the Find in Files filters offer. */
    suspend fun facets(root: FileEntry): SearchFacets = facetsOf(walkFiles(root), root.path)

    /** Full-text search of [query] in the text files under [root]. */
    suspend fun findInFiles(root: FileEntry, query: String, ignoreCase: Boolean, limit: Int): List<TextMatch>

    /** Full-text search with the Find in Files options; the plain search fields keep their behavior. */
    suspend fun findInFiles(root: FileEntry, options: FindOptions, limit: Int): List<TextMatch> =
        findInFiles(root, options.text, options.ignoreCase, limit)

    /**
     * Streams the matches of [options] in [SearchBatch]es as the server finds them, instead of waiting for the
     * whole answer of [findInFiles]. Null for a search that cannot stream: the caller runs the blocking one.
     */
    fun findInFilesStream(root: FileEntry, options: FindOptions, limit: Int): SearchStream? = null

    /** Declarations of the Kotlin and Java sources under [root]. */
    suspend fun symbols(root: FileEntry): List<FileSymbol>

    /** Defaults of the search parameters. */
    companion object {
        /** Maximum number of files listed by [walkFiles] by default. */
        const val DEFAULT_WALK_LIMIT = 200_000
    }
}

/** Directories that are never indexed nor searched. */
val IGNORED_DIRECTORIES =
    setOf(".git", ".hg", ".svn", ".idea", ".gradle", ".kotlin", "build", "out", "node_modules", "target")
