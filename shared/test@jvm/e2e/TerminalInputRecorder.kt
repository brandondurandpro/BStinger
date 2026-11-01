package org.example.kotlineditor.e2e

import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalLauncher
import org.example.kotlineditor.model.TerminalProcess
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sits between the UI and the real terminal launcher (which still goes to the server) and records what the UI asks
 * the shells to receive: the keys it sends and the text it writes. Tests can then check that a key typed no
 * character, whatever the shell does with it (bash, dash and cmd.exe edit their line differently).
 */
class TerminalInputRecorder(private val launcher: TerminalLauncher) : TerminalLauncher {
    private val sentKeys = CopyOnWriteArrayList<TerminalKeyEvent>()
    private val writtenTexts = CopyOnWriteArrayList<String>()

    /** Keys sent to any shell, oldest first. */
    val keys: List<TerminalKeyEvent> get() = sentKeys.toList()

    /** Text written to any shell (typed through the semantics, not as keys), oldest first. */
    val texts: List<String> get() = writtenTexts.toList()

    /** Forgets what was recorded so far. */
    fun clear() {
        sentKeys.clear()
        writtenTexts.clear()
    }

    override fun start(
        directory: String,
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): TerminalProcess = Recording(launcher.start(directory, columns, rows, cellWidthPx, cellHeightPx))

    private inner class Recording(private val process: TerminalProcess) : TerminalProcess by process {
        override fun write(bytes: ByteArray) {
            writtenTexts += bytes.decodeToString()
            process.write(bytes)
        }

        override fun sendKey(event: TerminalKeyEvent) {
            sentKeys += event
            process.sendKey(event)
        }
    }
}
