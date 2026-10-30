package org.example.kotlineditor.server

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.WireWriter
import org.example.kotlineditor.rpc.snapshot
import org.example.kotlineditor.server.ghostty.GhosttyEngine
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Bytes read from the PTY at once; also caps how much one GhosttyEngine.feed parses.
private const val READ_BUFFER = 64 * 1024

// Minimum delay between two screens: output faster than this is coalesced instead of flooding the client.
private const val FRAME_INTERVAL_MS = 16L

private const val ENDED = "This terminal has ended"

private const val CTRL_L: Byte = 0x0c

/**
 * A terminal of the server: a real PTY running the user's shell, and the libghostty-vt engine parsing what it
 * prints.
 *
 * The shell runs in a pseudo-terminal, so it is interactive, has colours and can run `vim`, `htop` or anything
 * else. Cells never travel: the server keeps the grid and sends complete snapshots (columns, rows, cursor, title,
 * scroll position, styled cells) to the client, at most once per [FRAME_INTERVAL_MS]. A client that reconnects
 * asks for [resume], which receives the last screen and, if the shell is over, the end of the stream.
 */
@Suppress("TooManyFunctions")
internal class ServerTerminal(
    private val id: Long,
    directory: String,
    columns: Int,
    rows: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
    private val push: (Frame) -> Unit,
) {
    private val state = Any()
    private val input = Any()
    private val process: PtyProcess
    private val engine: GhosttyEngine
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "editor-terminal-publish-$id").apply { isDaemon = true }
        }

    private var seq = 0L
    private var lastFrame: Frame? = null
    private var endFrame: Frame? = null
    private var finished = false
    private var publishScheduled = false
    private var destroyed = false

    init {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val command =
            if (windows) {
                listOf("cmd.exe", "/Q")
            } else {
                listOf(System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh")
            }
        val environment =
            System.getenv().toMutableMap().apply {
                put("TERM", "xterm-256color")
                put("COLORTERM", "truecolor")
            }
        process =
            PtyProcessBuilder(command.toTypedArray())
                .setEnvironment(environment)
                .setDirectory(directory)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setRedirectErrorStream(true)
                .setConsole(false)
                .setUseWinConPty(windows)
                .setWindowsAnsiColorEnabled(windows)
                .start()
        engine =
            GhosttyEngine(columns, rows, cellWidthPx, cellHeightPx) { bytes ->
                synchronized(input) {
                    runCatching {
                        process.outputStream.write(bytes)
                        process.outputStream.flush()
                    }
                }
            }
        publish(engine.snapshot())
        thread(name = "editor-terminal-$id", isDaemon = true) { pump() }
    }

    /** Writes raw bytes (escape sequences, responses) to the shell. */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureRunning()
        synchronized(input) {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
        refresh()
    }

    /** Encodes [event] for the running program, then writes the bytes it produced. */
    fun sendKey(event: TerminalKeyEvent) {
        ensureRunning()
        engine.encodeKey(event)
        refresh()
    }

    /** Pastes [text], with bracketed-paste markers when the program enabled them. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        ensureRunning()
        engine.paste(text)
        refresh()
    }

    /** Resizes the grid, reflows the scrollback and tells the shell about it. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        ensureRunning()
        engine.resize(columns, rows, cellWidthPx, cellHeightPx)
        process.setWinSize(WinSize(columns, rows))
        refresh()
    }

    /** Clears the screen and the scrollback; a full-screen program gets Ctrl+L instead, to redraw itself. */
    fun clear() {
        ensureRunning()
        if (!engine.clear()) write(byteArrayOf(CTRL_L))
        refresh()
    }

    /** Scrolls the viewport: positive goes up into the scrollback. */
    fun scroll(delta: Int) {
        ensureRunning()
        engine.scroll(delta)
        refresh()
    }

    /** Sends the last screen (and the end, if the shell exited) to a client that reconnected. */
    fun resume(lastSeq: Long) {
        synchronized(state) {
            lastFrame?.let { if (seq > lastSeq) push(it) }
            endFrame?.let(push)
        }
    }

    /** Kills the shell and its children; the pump thread then publishes the final screen and closes the engine. */
    fun destroy() {
        synchronized(state) {
            if (destroyed) return
            destroyed = true
        }
        scheduler.shutdownNow()
        runCatching { process.descendants().forEach { it.destroy() } }
        process.destroy()
    }

    private fun ensureRunning() {
        synchronized(state) { check(!finished) { ENDED } }
    }

    private fun pump() {
        try {
            process.inputStream.use { stream -> read(stream) }
        } finally {
            finish()
        }
    }

    private fun read(stream: InputStream) {
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val length =
                try {
                    stream.read(buffer)
                } catch (e: IOException) {
                    if (destroyed) -1 else throw e
                }
            if (length < 0) return
            if (length == 0) continue
            engine.feed(if (length == buffer.size) buffer else buffer.copyOf(length))
            refresh()
        }
    }

    private fun refresh() {
        synchronized(state) {
            if (finished || destroyed || publishScheduled) return
            publishScheduled = true
            scheduler.schedule({ publishLatest() }, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun publishLatest() {
        synchronized(state) {
            publishScheduled = false
            if (finished || destroyed) return
        }
        val snapshot = runCatching { engine.snapshot() }.getOrNull() ?: return
        publish(snapshot)
    }

    private fun finish() {
        val exitCode = runCatching { process.waitFor() }.getOrNull()
        val snapshot = runCatching { engine.snapshot(running = false, exitCode = exitCode) }.getOrNull()
        synchronized(state) {
            if (finished) return
            finished = true
            scheduler.shutdownNow()
            if (snapshot != null) {
                seq++
                val event = event(seq, snapshot)
                lastFrame = event
                push(event)
            }
            val end = Frame(FrameKind.End, id, WireWriter().long(seq).toByteArray())
            endFrame = end
            push(end)
        }
        runCatching { engine.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
    }

    private fun publish(snapshot: TerminalSnapshot) {
        synchronized(state) {
            if (finished || destroyed) return
            seq++
            val frame = event(seq, snapshot)
            lastFrame = frame
            push(frame)
        }
    }

    private fun event(seq: Long, snapshot: TerminalSnapshot): Frame =
        Frame(FrameKind.Event, id, WireWriter().long(seq).snapshot(snapshot).toByteArray())
}
