package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.WireWriter
import org.example.kotlineditor.rpc.strings
import java.io.File
import kotlin.concurrent.thread

private const val MAX_BATCH = 512

/**
 * A shell process whose output is pushed to the client as numbered batches. The last batches are kept so a
 * client coming back after a network failure gets exactly what it missed.
 */
internal class ServerShell(
    private val id: Long,
    directory: String,
    private val backlogSize: Int,
    private val push: (Frame) -> Unit,
) {
    private val process: Process
    private val input: java.io.BufferedWriter
    private val backlog = ArrayDeque<Pair<Long, List<String>>>()
    private var seq = 0L
    private var endSeq = -1L

    init {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val unixShell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh"
        val command = if (windows) listOf("cmd.exe", "/Q") else listOf(unixShell)
        process = ProcessBuilder(command).directory(File(directory)).redirectErrorStream(true).start()
        input = process.outputStream.bufferedWriter()
        thread(name = "editor-shell-$id", isDaemon = true) { pump() }
    }

    fun send(line: String) {
        input.write(line)
        input.newLine()
        input.flush()
    }

    /** Sends everything after [lastSeq]; flags a reset when part of it is no longer in the backlog. */
    fun replay(lastSeq: Long) =
        synchronized(this) {
            val missed = backlog.filter { it.first > lastSeq }
            missed.forEachIndexed { index, (s, lines) -> push(event(s, reset = index == 0 && s > lastSeq + 1, lines)) }
            if (endSeq >= 0) push(end())
        }

    fun destroy() {
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }

    private fun pump() {
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                // Group what is already available into one frame: a chatty command does not cost a frame per line.
                val batch = arrayListOf(line)
                var more = true
                while (more && batch.size < MAX_BATCH && reader.ready()) {
                    val next = reader.readLine()
                    if (next == null) more = false else batch += next
                }
                publish(batch)
                line = reader.readLine()
            }
        }
        synchronized(this) {
            endSeq = seq
            push(end())
        }
    }

    private fun publish(lines: List<String>) =
        synchronized(this) {
            seq++
            backlog.addLast(seq to lines)
            while (backlog.size > backlogSize) backlog.removeFirst()
            push(event(seq, reset = false, lines))
        }

    private fun event(seq: Long, reset: Boolean, lines: List<String>) =
        Frame(
            FrameKind.Event,
            id,
            WireWriter()
                .long(seq)
                .bool(reset)
                .strings(lines)
                .toByteArray(),
        )

    private fun end() = Frame(FrameKind.End, id, WireWriter().long(endSeq).toByteArray())
}
