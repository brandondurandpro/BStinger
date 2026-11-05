package org.example.kotlineditor.server

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

// Once Git has exited, how long its standard error may still take to reach its end (a helper that keeps it open).
private const val ERRORS_JOIN_MILLIS = 1000L

// What is kept of Git's standard error: enough for its message, never a flood in memory.
private const val MAX_ERROR_CHARS = 64 * 1024
private const val READ_BUFFER_CHARS = 8 * 1024

/** Starts [builder] with its standard input closed: a command, hook or helper that reads it gets its end at once. */
internal fun startGit(builder: ProcessBuilder): Process =
    builder.start().apply {
        runCatching { outputStream.close() }
    }

/** Stops [process] and the processes it started (a hook, a helper, the shell of an alias), which outlive it else. */
internal fun destroyTree(process: Process) {
    val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

/** How a Git command ended: its exit code, the lines it printed and its standard error (the start of it). */
internal class GitRun(val exitCode: Int, val lines: List<String>, val errors: String) {
    /** The output as runGit returns it: each line ended by `\n`. */
    val text: String get() = lines.joinToString("") { it + "\n" }
}

/**
 * Runs Git in [root] to its end, both of its outputs read as they come, so that a full pipe never blocks it. It is
 * stopped, with the processes it started, when it runs longer than [timeoutSeconds] (an [IOException]) or when the
 * calling thread is interrupted, which is how `runInterruptible` cancels a request (an [InterruptedException]).
 */
internal fun execGit(root: String, args: List<String>, timeoutSeconds: Long): GitRun {
    val process = startGit(gitProcess(root, args))
    var finished = false
    try {
        val output = LineDrain(process.inputStream)
        val errors = TextDrain(process.errorStream)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        // The output is complete when Git closes it, a moment after its exit.
        val complete = exited && output.await(millisUntil(deadline))
        if (!complete) throw IOException("git ${args.firstOrNull()} timed out after $timeoutSeconds s")
        errors.await(ERRORS_JOIN_MILLIS)
        finished = true
        return GitRun(process.exitValue(), output.lines(), errors.text())
    } finally {
        // A timeout or a cancellation: nothing must keep running for a request nobody waits for.
        if (!finished) destroyTree(process)
    }
}

private fun millisUntil(deadline: Long): Long {
    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
    return left.coerceAtLeast(1)
}

// Reads the lines of a stream of the process on a daemon thread, to its end.
private class LineDrain(stream: InputStream) {
    private val lines = ArrayList<String>()
    private val thread =
        daemon {
            stream.bufferedReader().forEachLine { line -> synchronized(lines) { lines += line } }
        }

    fun await(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }

    fun lines(): List<String> = synchronized(lines) { ArrayList(lines) }
}

// Reads a stream of the process on a daemon thread to its end, keeping its first [MAX_ERROR_CHARS] characters only.
private class TextDrain(stream: InputStream) {
    private val text = StringBuilder()
    private val thread =
        daemon {
            val reader = stream.bufferedReader()
            val buffer = CharArray(READ_BUFFER_CHARS)
            var count = reader.read(buffer)
            while (count >= 0) {
                synchronized(text) {
                    val room = (MAX_ERROR_CHARS - text.length).coerceIn(0, count)
                    text.appendRange(buffer, 0, room)
                }
                count = reader.read(buffer)
            }
        }

    fun await(millis: Long) {
        thread.join(millis)
    }

    fun text(): String = synchronized(text) { text.toString() }
}

// A stream closed under the reader (the process killed) simply ends the reading.
private fun daemon(read: () -> Unit): Thread =
    Thread { runCatching(read) }.apply {
        isDaemon = true
        name = "git-output"
        start()
    }
