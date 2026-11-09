package org.example.kotlineditor.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.toJson
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Minimum delay between two chunks: output faster than this is coalesced instead of flooding the client.
private const val CHUNK_INTERVAL_MS = 30L

// A line longer than this is cut: nobody reads it, and the UI draws it.
private const val MAX_LINE = 4000

// Lines kept for a client that reconnects; the oldest chunks are dropped beyond.
private const val MAX_KEPT_LINES = 200_000

private const val NOT_RUNNING = "Nothing to run"

/**
 * Runs of a session, keyed by the id of the request that started them (like [TerminalRegistry]'s terminals): each
 * one is a process next to the project's files, whose output is pushed as chunks and kept for [resume]. A run that
 * is gone (the server restarted, its sessions with it) answers nothing, and the client shows it as lost.
 */
internal class RunRegistry(private val push: (Frame) -> Unit) {
    private val runs = ConcurrentHashMap<Long, ServerRun>()

    /** Starts [command] in [directory] as the run [id]; the one this id had before (never in practice) stops. */
    fun start(id: Long, directory: String, command: List<String>) {
        stop(id)
        require(command.isNotEmpty()) { NOT_RUNNING }
        runs[id] = ServerRun(id, directory, command, push)
    }

    fun stop(id: Long) {
        runs[id]?.stop()
    }

    fun stopAll() {
        runs.values.forEach { it.stop() }
        runs.clear()
    }

    /** The chunks of the run [id] after [lastSeq], then its end; nothing when the run is already gone. */
    fun resume(id: Long, lastSeq: Long) {
        runs[id]?.resume(lastSeq)
    }
}

/** A chunk kept for the reconnections: the frame to replay, and how many lines it holds. */
private class KeptChunk(val lines: Int, val frame: Frame)

/**
 * One run: a process whose output and error output are merged, read line by line and pushed as chunks at most
 * once per [CHUNK_INTERVAL_MS]. The last chunk says how it ended (exit code, or the reason it could not start).
 */
private class ServerRun(
    private val id: Long,
    directory: String,
    command: List<String>,
    private val push: (Frame) -> Unit,
) {
    private val lock = Any()
    private val kept = TreeMap<Long, KeptChunk>()
    private val pending = ArrayList<String>()
    private var seq = 0L
    private var keptLines = 0
    private var endFrame: Frame? = null
    private var stopped = false
    private var flushScheduled = false
    private var process: Process? = null
    private var processStart = 0L

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "editor-run-publish-$id").apply { isDaemon = true }
        }

    init {
        val builder = ProcessBuilder(command).directory(File(directory)).redirectErrorStream(true)
        val started =
            try {
                builder.start()
            } catch (failure: IOException) {
                val reason = failure.message ?: failure::class.simpleName.orEmpty()
                finish(exitCode = null, error = reason)
                null
            }
        if (started != null) {
            process = started
            processStart = runCatching { OrphanReaper.startOf(started.toHandle()) }.getOrDefault(0L)
            thread(name = "editor-run-$id", isDaemon = true) { pump(started) }
        }
    }

    /** Stops the process and its children; the pump then publishes the end. */
    fun stop() {
        val running =
            synchronized(lock) {
                stopped = true
                process
            }
        if (running == null) return
        runCatching { OrphanReaper.killTree(running.toHandle(), processStart) }
        running.destroyForcibly()
    }

    /** Replays the chunks after [lastSeq], then the end frame; the client asks this after a reconnection. */
    fun resume(lastSeq: Long) {
        val replay = synchronized(lock) { kept.tailMap(lastSeq, false).values.map { it.frame } }
        replay.forEach(push)
        val end = synchronized(lock) { endFrame }
        if (end != null) push(end)
    }

    private fun pump(started: Process) {
        try {
            started.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line.take(MAX_LINE))
                }
            }
        } catch (expected: IOException) {
            // The pipe broke when the process was killed: the end is reported below.
        }
        val exitCode = runCatching { started.waitFor() }.getOrNull()
        val wasStopped = synchronized(lock) { stopped }
        finish(exitCode = if (wasStopped) null else exitCode, error = null)
    }

    private fun append(line: String) {
        val schedule =
            synchronized(lock) {
                pending += line
                val first = !flushScheduled
                flushScheduled = true
                first
            }
        if (schedule) runCatching { scheduler.schedule({ flush() }, CHUNK_INTERVAL_MS, TimeUnit.MILLISECONDS) }
    }

    private fun flush() {
        synchronized(lock) {
            flushScheduled = false
            if (endFrame != null) return
            if (pending.isNotEmpty()) publish(finished = false, exitCode = null, error = null)
        }
    }

    private fun finish(exitCode: Int?, error: String?) {
        scheduler.shutdown()
        synchronized(lock) {
            if (endFrame != null) return
            val last = publish(finished = true, exitCode = exitCode, error = error)
            val end = Frame(FrameKind.End, id, buildJsonObject { put("seq", last) })
            endFrame = end
            push(end)
        }
    }

    // Publishes the pending lines as the next chunk; called with the lock held, so chunks leave in order.
    private fun publish(finished: Boolean, exitCode: Int?, error: String?): Long {
        val lines = pending.toList()
        pending.clear()
        seq++
        val chunk = RunChunk(seq, lines, finished, exitCode, error)
        val frame = Frame(FrameKind.Event, id, buildJsonObject { put("seq", seq); put("chunk", chunk.toJson()) })
        kept[seq] = KeptChunk(lines.size, frame)
        keptLines += lines.size
        dropOldest()
        push(frame)
        return seq
    }

    // The oldest chunks go once too many lines are kept; the newest always stays.
    private fun dropOldest() {
        while (keptLines > MAX_KEPT_LINES && kept.size > 1) {
            val oldest = kept.pollFirstEntry() ?: return
            keptLines -= oldest.value.lines
        }
    }
}
