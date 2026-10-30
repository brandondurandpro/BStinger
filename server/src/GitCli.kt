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
    val reader =
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }
        }
    reader.isDaemon = true
    reader.start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("git ${args.firstOrNull()} timed out")
    }
    reader.join(READER_JOIN_MILLIS)
    val text = synchronized(output) { output.toString() }
    if (process.exitValue() != 0) {
        val detail = text.trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${process.exitValue()}"
        throw IOException("git ${args.firstOrNull()}: $detail")
    }
    return text
}
