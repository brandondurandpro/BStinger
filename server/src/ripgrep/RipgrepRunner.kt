package org.example.kotlineditor.server.ripgrep

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What to search for. Every Find in Files option becomes a field here, translated into flags by
 * [RipgrepRunner.arguments].
 */
internal data class RipgrepQuery(
    /** Project root searched; results outside it are dropped. */
    val root: Path,
    /** Text searched: literally, unless [regex]. */
    val text: String,
    /** Whether letter case is ignored. */
    val ignoreCase: Boolean,
    /** Whether only matches of whole words (letters, digits, `_`) count. */
    val wholeWord: Boolean = false,
    /** Whether [text] is a regular expression instead of literal text. */
    val regex: Boolean = false,
    /** Only files whose name matches this glob (`*.kt`), or null for all of them. */
    val filePattern: String? = null,
    /** Directory searched, relative to [root] (`src`), or null for its whole root. */
    val scope: String? = null,
    /** Whether ignored files (Git-ignored, `.git`, build output) are searched too. */
    val includeIgnored: Boolean = false,
)

/** ripgrep could not run the search: the caller falls back to the built-in search. */
internal class RipgrepException(message: String) : IOException(message)

private fun MutableList<String>.addExcludedDirectories() {
    for (directory in IGNORED_DIRECTORIES) {
        add("--glob")
        add("!$directory/")
    }
}

/**
 * Runs `rg --json` (directly, never through a shell) and turns its output into [RipgrepMatch]es.
 *
 * Files are filtered like the built-in search, plus ripgrep's ignore files: hidden files are searched, `.git`,
 * build output and dependency directories ([IGNORED_DIRECTORIES]) are not, and neither are files larger than
 * [maxFileSize] or binary files. `.gitignore` applies inside Git repositories, `.ignore` and `.rgignore` everywhere.
 */
internal class RipgrepRunner(
    private val executable: Path,
    private val maxFileSize: Long,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    /** Called with every ripgrep process started (tests check that it ends). */
    private val onStart: (ProcessHandle) -> Unit = {},
) {
    /**
     * Command line for [query]. `--max-columns` is not used: it has no effect with `--json`, so long lines are
     * bounded when reading ([maxLineBytes]). The query is not on the command line but read from standard input
     * (`--file -`, see [matches]): it can never be taken for an option, and Windows' argument quoting (which Java
     * does not escape: `"` would be mangled) never applies to it. Unless [RipgrepQuery.regex], the query is
     * searched literally (`--fixed-strings`), which holds with stdin patterns as with any other.
     */
    fun arguments(query: RipgrepQuery): List<String> =
        buildList {
            add(executable.toString())
            add("--json")
            add("--no-config")
            if (!query.regex) add("--fixed-strings")
            add(if (query.ignoreCase) "--ignore-case" else "--case-sensitive")
            if (query.wholeWord) add("--word-regexp")
            add("--hidden")
            if (query.includeIgnored) {
                add("--no-ignore")
            } else {
                addExcludedDirectories()
            }
            // The file glob comes last, after the ignored directories', so that it decides when they disagree.
            if (query.filePattern != null) {
                add("--glob")
                add(checkNotNull(query.filePattern))
            }
            add("--max-filesize")
            add(maxFileSize.toString())
            add("--file")
            add("-")
            add("--")
            add(searchDirectory(query).toString())
        }

    // The directory ripgrep searches: the root, or the scope inside it (normalized as well).
    private fun searchDirectory(query: RipgrepQuery): Path {
        val scope = query.scope ?: return query.root
        return query.root
            .resolve(scope)
            .toAbsolutePath()
            .normalize()
    }

    /**
     * Command line for [files]: the same filters as the search ([IGNORED_DIRECTORIES] are excluded explicitly,
     * hidden files are listed, ignore files apply by themselves), without the search-only ones: no size limit,
     * no readability rules on a file that is only listed.
     */
    fun filesArguments(root: Path): List<String> =
        buildList {
            add(executable.toString())
            add("--files")
            add("--no-config")
            add("--hidden")
            addExcludedDirectories()
            add("--")
            add(root.toString())
        }

    /**
     * Matching lines, as ripgrep finds them (several threads: files come in no particular order). The process is
     * killed with its children as soon as the collection stops early: limit reached (`take`), timeout, cancellation.
     * Fails with [RipgrepException] or [IOException] when ripgrep cannot start or ends with an error.
     */
    fun matches(query: RipgrepQuery): Flow<RipgrepMatch> =
        channelFlow {
            val root = query.root.toAbsolutePath().normalize()
            // Started without suspending (on the IO dispatcher, see flowOn): a cancellation cannot come between
            // the start and the `try` that guarantees the kill.
            val process = ProcessBuilder(arguments(query.copy(root = root))).start()
            try {
                onStart(process.toHandle())
                // One pattern per line: the query is a single line (the search field has no line breaks).
                process.outputStream.use { it.write(query.text.toByteArray(Charsets.UTF_8)) }
                val errors = async(Dispatchers.IO) { head(process.errorStream) }
                val summary = AtomicBoolean()
                launch(Dispatchers.IO) { forward(process.inputStream, root, summary) }.join()
                val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
                // 2 after a summary: some files could not be read, the others were searched (like grep's 2).
                val partial = code == EXIT_ERROR && summary.get()
                val succeeded = code == EXIT_MATCHES || code == EXIT_NO_MATCH || partial
                if (!succeeded) throw RipgrepException("ripgrep exited with $code: ${errors.await().trim()}")
            } finally {
                destroyTree(process)
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<RipgrepMatch>.forward(output: InputStream, root: Path, summary: AtomicBoolean) {
        val lines = JsonLineReader(output, maxLineBytes)
        try {
            while (true) {
                val line = lines.readLine() ?: break
                when (val event = RipgrepJson.parse(line)) {
                    is RipgrepEvent.Match -> event.match?.takeIf { isUnder(root, it.path) }?.let { send(it) }
                    RipgrepEvent.Summary -> summary.set(true)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // The stream may close under us when the process is killed after the search was cancelled.
            if (isActive) throw e
        }
    }

    /**
     * Files listed by `rg --files` under [root]: every regular file ripgrep sees (its ignore files applied by
     * themselves), as plain paths on stdout, in no particular order. The filters are the search's: hidden files
     * are listed, the ignored directories are not. No size filter: a listed file is not read. Fails with
     * [RipgrepException] or [IOException] when ripgrep cannot start or ends with an error.
     */
    fun files(root: Path): Flow<String> =
        channelFlow {
            val rootDirectory = root.toAbsolutePath().normalize()
            // Started without suspending (on the IO dispatcher, see flowOn): a cancellation cannot come between
            // the start and the `try` that guarantees the kill.
            val process = ProcessBuilder(filesArguments(rootDirectory)).start()
            try {
                onStart(process.toHandle())
                val errors = async(Dispatchers.IO) { head(process.errorStream) }
                val listed = async(Dispatchers.IO) { forwardListedPaths(process.inputStream, rootDirectory) }
                val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
                if (!filesSucceeded(code, listed.await())) {
                    throw RipgrepException("ripgrep exited with $code: ${errors.await().trim()}")
                }
            } finally {
                destroyTree(process)
            }
        }.flowOn(Dispatchers.IO)

    // The paths ripgrep listed (under [root], possibly after ripgrep could not look everywhere), and their count.
    private suspend fun ProducerScope<String>.forwardListedPaths(output: InputStream, root: Path): Int {
        val lines = JsonLineReader(output, maxLineBytes)
        var listed = 0
        try {
            while (true) {
                val line = lines.readLine() ?: break
                if (line.isNotEmpty() && isUnder(root, line)) {
                    listed++
                    send(line)
                }
            }
        } catch (e: IOException) {
            // The stream may close under us when the process is killed after the walk was cancelled.
            if (isActive) throw e
        }
        return listed
    }

    // Whether ripgrep listed what it looked for: an error is tolerated when some files were already listed.
    // Errors with nothing to show leave the whole query to the built-in walk, which handles unreadable entries.
    private fun filesSucceeded(code: Int, listed: Int): Boolean =
        when (code) {
            EXIT_MATCHES, EXIT_NO_MATCH -> true
            EXIT_ERROR -> listed > 0
            else -> false
        }

    internal companion object {
        /** Longest `rg --json` message read (4 MiB): a matching line longer than that is skipped. */
        const val DEFAULT_MAX_LINE_BYTES = 4 * 1024 * 1024

        private const val EXIT_MATCHES = 0
        private const val EXIT_NO_MATCH = 1
        private const val EXIT_ERROR = 2
        private const val MAX_ERROR_OUTPUT = 4096

        /** Kills [process] and every process it started, not the strangers that once had a parent of its pid. */
        fun destroyTree(process: Process) = OrphanReaper.killTree(process.toHandle())

        // Paths are confined to the searched root: anything else (it should not happen) is dropped.
        private fun isUnder(root: Path, path: String): Boolean =
            try {
                Paths
                    .get(path)
                    .toAbsolutePath()
                    .normalize()
                    .startsWith(root)
            } catch (_: InvalidPathException) {
                false
            }

        // Keeps the first error messages and drains the rest, so ripgrep never blocks on a full stderr pipe.
        private fun head(errors: InputStream): String =
            try {
                errors.use {
                    val head = it.readNBytes(MAX_ERROR_OUTPUT)
                    it.transferTo(OutputStream.nullOutputStream())
                    String(head, Charsets.UTF_8)
                }
            } catch (_: IOException) {
                "" // Closed because the process was killed.
            }
    }
}
