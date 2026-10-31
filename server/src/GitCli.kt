package org.example.kotlineditor.server

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Length of the short commit hashes shown by the IDE. */
internal const val SHORT_HASH_LENGTH = 8

private const val TIMEOUT_SECONDS = 60L
private const val READER_JOIN_MILLIS = 1000L

/** Runs Git in [root] and returns its lines, or `null` when it fails (read-only queries). */
internal fun gitOutput(root: String, vararg args: String): List<String>? =
    runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(File(root)).start()
        val output = process.inputStream.bufferedReader().readLines()
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()

/** Runs Git in [root] and returns its output; throws [IOException] with Git's message when it fails. */
internal fun runGit(root: String, vararg args: String): String = runGit(root, TIMEOUT_SECONDS, *args)

/** Same as [runGit], but gives up after [timeoutSeconds]: network operations can hang. */
internal fun runGit(root: String, timeoutSeconds: Long, vararg args: String): String {
    val process =
        ProcessBuilder(listOf("git") + args)
            .directory(File(root))
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
            .start()
    val output = StringBuilder()
    val errors = StringBuilder()
    val readers = listOf(drain(process.inputStream, output), drain(process.errorStream, errors))
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("git ${args.firstOrNull()} timed out")
    }
    readers.forEach { it.join(READER_JOIN_MILLIS) }
    val text = synchronized(output) { output.toString() }
    if (process.exitValue() != 0) {
        val detail = failureDetail(synchronized(errors) { errors.toString() }, text) ?: "exit ${process.exitValue()}"
        throw IOException("git ${args.firstOrNull()}: $detail")
    }
    return text
}

// Copies a stream of the process on a daemon thread, so that a full pipe never blocks Git.
private fun drain(stream: java.io.InputStream, text: StringBuilder): Thread =
    Thread {
        runCatching {
            stream.bufferedReader().forEachLine { line -> synchronized(text) { text.appendLine(line) } }
        }
    }.apply {
        isDaemon = true
        start()
    }

// Git's reason for a failure: its first `error:` or `fatal:` line (later ones are often just "Aborting"), or else
// the last line it printed.
private fun failureDetail(errors: String, output: String): String? {
    val lines = (errors.lines() + output.lines()).map(String::trim).filter(String::isNotEmpty)
    return lines.firstOrNull { it.startsWith("error:") || it.startsWith("fatal:") } ?: lines.lastOrNull()
}
