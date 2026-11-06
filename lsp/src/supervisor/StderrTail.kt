package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

// Enough for a stack trace and the error block above it.
private const val STDERR_TAIL = 200

// How long a crash waits for the last lines of stderr; a child keeping the pipe open must not delay it.
private const val DRAIN_WAIT_MS = 500L

/**
 * The stderr of the processes of the server [serverId]: always read, since a server whose stderr pipe is full blocks,
 * into its [log]; the last lines of the current process tell why it crashed.
 */
internal class StderrTail(private val serverId: String, private val log: LogRing) {
    @Volatile private var tail = LogRing(STDERR_TAIL)

    @Volatile private var drainer: Thread? = null

    /** Reads [input], the stderr of a new process, on a thread of its own. */
    fun drain(input: InputStream) {
        val lines = LogRing(STDERR_TAIL)
        tail = lines
        drainer = thread(name = "lsp-stderr-$serverId", isDaemon = true) { readLines(input, lines) }
    }

    /** What the process wrote last on stderr, once read to its end: the exit may be reported before the last lines. */
    suspend fun explanation(): String? {
        withContext(Dispatchers.IO) { drainer?.join(DRAIN_WAIT_MS) }
        return CrashReason.explain(tail.lines())
    }

    private fun readLines(stream: InputStream, lines: LogRing) {
        val splitter =
            LogLines { line ->
                log.add(line)
                lines.add(line)
            }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            stream.use { input ->
                var read = input.read(buffer)
                while (read >= 0) {
                    splitter.feed(buffer, read)
                    read = input.read(buffer)
                }
            }
        } catch (_: IOException) {
            // The process is gone.
        }
        splitter.flush()
    }
}
