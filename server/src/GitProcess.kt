package org.example.kotlineditor.server

import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

// Once Git has exited, how long its standard error may still take to reach its end (a helper that keeps it open).
private const val ERRORS_JOIN_MILLIS = 1000L

// Once Git has exited, how long its output may still take to reach its end: a process it started in the background
// may keep it open for as long as it runs, which must not hold the request until its timeout.
private const val OUTPUT_JOIN_MILLIS = 5000L

// What is kept of Git's standard error: enough for its message, never a flood in memory.
private const val MAX_ERROR_CHARS = 64 * 1024
private const val READ_BUFFER_CHARS = 8 * 1024

/** A Git process, with its start time read while it ran: what [destroyTree] needs to find its children safely. */
internal class GitChild(val process: Process) {
    private val start = OrphanReaper.startOf(process.toHandle())

    /**
     * Stops Git and the processes it started (a hook, a helper, the shell of an alias), which outlive it else. Only
     * the processes started after Git are taken: on Windows, the descendants of a process that has exited are every
     * process whose long dead parent had the same pid, the CI runner for instance (see [OrphanReaper.ownDescendants]).
     */
    fun destroyTree() {
        OrphanReaper.killTree(process.toHandle(), start)
    }
}

/** Starts [builder] with its standard input closed: a command, hook or helper that reads it gets its end at once. */
internal fun startGit(builder: ProcessBuilder): GitChild {
    val process = builder.start()
    // Read at once, while Git surely runs.
    val child = GitChild(process)
    runCatching { process.outputStream.close() }
    return child
}

/** How a Git command ended: its exit code, the lines it printed and its standard error (the start of it). */
internal class GitRun(val exitCode: Int, val lines: List<String>, val errors: String) {
    /** The output as runGit returns it: each line ended by `\n`. */
    val text: String get() = lines.joinToString("") { it + "\n" }
}

/**
 * Runs Git in [root] to its end, both of its outputs read as they come, so that a full pipe never blocks it. It is
 * stopped, with the processes it started, when it runs longer than [timeoutSeconds] or leaves its output open after
 * its exit (an [IOException]), or when the calling thread is interrupted, which is how `runInterruptible` cancels a
 * request (an [InterruptedException]). Nothing waits for the pipes of a Git that was stopped.
 */
internal fun execGit(root: String, args: List<String>, timeoutSeconds: Long): GitRun {
    val child = startGit(gitProcess(root, args))
    val process = child.process
    val command = "git ${args.firstOrNull()}"
    var finished = false
    try {
        val output = LineDrain(process.inputStream)
        val errors = TextDrain(process.errorStream)
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) throw IOException("$command timed out after $timeoutSeconds s")
        // The output is complete when it reaches its end, a moment after Git's exit.
        val complete = output.await(OUTPUT_JOIN_MILLIS)
        if (!complete) throw IOException("$command exited, but a process it started kept its output open")
        errors.await(ERRORS_JOIN_MILLIS)
        finished = true
        return GitRun(process.exitValue(), output.lines(), errors.text())
    } finally {
        // A timeout or a cancellation: nothing must keep running for a request nobody waits for.
        if (!finished) child.destroyTree()
    }
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
