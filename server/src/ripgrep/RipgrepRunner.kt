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
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What to search for. The Find in Files options still to come (whole word, regular expression, file mask, scope,
 * ignored files included) become fields here, translated into flags by [RipgrepRunner.arguments].
 */
internal data class RipgrepQuery(
    /** Directory searched (the project root); results outside it are dropped. */
    val root: Path,
    /** Text searched, literally. */
    val text: String,
    /** Whether letter case is ignored. */
    val ignoreCase: Boolean,
)

/** ripgrep could not run the search: the caller falls back to the built-in search. */
internal class RipgrepException(message: String) : IOException(message)

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
     * does not escape: `"` would be mangled) never applies to it.
     */
    fun arguments(query: RipgrepQuery): List<String> =
        buildList {
            add(executable.toString())
            add("--json")
            add("--no-config")
            add("--fixed-strings")
            add(if (query.ignoreCase) "--ignore-case" else "--case-sensitive")
            add("--hidden")
            for (directory in IGNORED_DIRECTORIES) {
                add("--glob")
                add("!$directory/")
            }
            add("--max-filesize")
            add(maxFileSize.toString())
            add("--file")
            add("-")
            add("--")
            add(query.root.toString())
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

    internal companion object {
        /** Longest `rg --json` message read (4 MiB): a matching line longer than that is skipped. */
        const val DEFAULT_MAX_LINE_BYTES = 4 * 1024 * 1024

        private const val EXIT_MATCHES = 0
        private const val EXIT_NO_MATCH = 1
        private const val EXIT_ERROR = 2
        private const val MAX_ERROR_OUTPUT = 4096

        /** Kills [process] and every process it started. */
        fun destroyTree(process: Process) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

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
