package org.example.kotlineditor.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import org.example.kotlineditor.model.AgentTerminalOutput
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// How often the transcript shows the output of a running command, at most.
private const val CHANGE_DELAY_MS = 100L

// Characters of the output the transcript shows: its end, the agent reads the rest with `terminal/output`.
private const val SHOWN_CHARS = 16_000

private const val READ_BUFFER = 8_192

// Bytes of output kept when the agent sets no limit.
private const val DEFAULT_BYTE_LIMIT = 4L * 1024 * 1024

/**
 * A command an agent runs with `terminal/create`, without a terminal emulator: its output (stdout and stderr, in the
 * order they come) is kept up to [byteLimit] bytes, the oldest dropped first, and [onChange] hears that it changed,
 * at most every 100 ms, and once more when it exits.
 */
internal class AgentTerminal(
    val id: String,
    /** The session that started it. */
    val session: String,
    private val process: Process,
    byteLimit: Long?,
    private val scope: CoroutineScope,
    private val onChange: (AgentTerminal) -> Unit,
) {
    private val lock = Any()

    // Read while it runs: a command that exited has no start time, and its pid may name another process by then.
    private val processStart = OrphanReaper.startOf(process.toHandle())
    private val output = StringBuilder()

    // A command that writes forever does not fill the memory of the server.
    private val byteLimit = byteLimit ?: DEFAULT_BYTE_LIMIT

    // The output kept starts at [start]: what comes before it was dropped, and goes once it is the larger part.
    private var start = 0
    private var bytes = 0L
    private var truncated = false
    private val changing = AtomicBoolean(false)
    private val exit = CompletableDeferred<Int>()

    @Volatile
    private var exitCode: Int? = null

    init {
        thread(name = "agent-terminal-$id", isDaemon = true) {
            runCatching { read() }
            // Every byte read before the exit status is known.
            val code = process.waitFor()
            exitCode = code
            exit.complete(code)
            onChange(this)
        }
    }

    /** Its output so far, whether its beginning was dropped, and its exit code once it exited. */
    fun output(): Snapshot = synchronized(lock) { Snapshot(output.substring(start), truncated, exitCode) }

    /** What the transcript shows of it: the end of its output. */
    fun shown(): AgentTerminalOutput =
        synchronized(lock) {
            val from = maxOf(start, output.length - SHOWN_CHARS)
            val cut = truncated || from > start
            AgentTerminalOutput(output.substring(from), exitCode, cut)
        }

    /** Waits for the command to exit, and returns its exit code. */
    suspend fun waitForExit(): Int = exit.await()

    /** Stops the command and the programs it started; its output stays readable. */
    fun kill() {
        val children = OrphanReaper.ownDescendants(process.toHandle(), processStart)
        children.forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private fun read() {
        val reader = process.inputStream.reader(Charsets.UTF_8)
        val buffer = CharArray(READ_BUFFER)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            append(String(buffer, 0, count))
            changed()
        }
    }

    private fun append(chunk: String) {
        synchronized(lock) {
            output.append(chunk)
            bytes += utf8Size(chunk, 0, chunk.length)
            val limit = byteLimit
            // The oldest characters go first, whole: a character is never cut in two.
            while (bytes > limit && start < output.length) {
                val point = output.codePointAt(start)
                val width = Character.charCount(point)
                bytes -= utf8Size(output, start, start + width)
                start += width
                truncated = true
            }
            if (start > output.length / 2) {
                output.delete(0, start)
                start = 0
            }
        }
    }

    // Coalesces the changes of the next 100 ms into one.
    private fun changed() {
        if (!changing.compareAndSet(false, true)) return
        scope.launch {
            delay(CHANGE_DELAY_MS)
            changing.set(false)
            onChange(this@AgentTerminal)
        }
    }

    private fun utf8Size(text: CharSequence, from: Int, to: Int): Long {
        val part = text.subSequence(from, to).toString()
        return part.toByteArray(Charsets.UTF_8).size.toLong()
    }

    /** What [output] returns. */
    class Snapshot(val text: String, val truncated: Boolean, val exitCode: Int?)
}
