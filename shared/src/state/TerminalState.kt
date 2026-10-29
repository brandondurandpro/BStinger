package org.example.kotlineditor.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.ShellLauncher
import org.example.kotlineditor.model.ShellProcess

/** The Terminal tool window: one shell per project, started the first time the window is shown. */
@Stable
class TerminalState(
    private val directory: String,
    private val launcher: ShellLauncher,
    private val scope: CoroutineScope,
) {
    val lines = mutableStateListOf<String>()
    val listState = LazyListState()

    var running by mutableStateOf(false)
        private set

    private var process: ShellProcess? = null
    private var job: Job? = null
    private val history = ArrayList<String>()
    private var historyIndex = 0

    fun ensureStarted() {
        if (process != null) return
        val started =
            runCatching { launcher.start(directory) }.getOrElse {
                lines += "Cannot start a shell: ${it.message}"
                return
            }
        process = started
        running = true
        job =
            scope.launch {
                try {
                    // Lines are appended in bursts so a chatty command does not trigger one recomposition per line.
                    started.output.buffer(capacity = 1024).collect { line ->
                        lines += line
                        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
                    }
                    lines += "[Process finished]"
                } finally {
                    running = false
                    process = null
                }
            }
    }

    fun send(command: String) {
        ensureStarted()
        lines += "$ $command"
        if (command.isNotBlank()) history += command
        historyIndex = history.size
        process?.send(command)
    }

    /** Up/Down in the input field walk through previous commands. */
    fun historyStep(delta: Int): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex + delta).coerceIn(0, history.size)
        return history.getOrNull(historyIndex) ?: ""
    }

    fun clear() = lines.clear()

    fun stop() {
        job?.cancel()
        process?.destroy()
        process = null
        running = false
    }

    private companion object {
        const val MAX_LINES = 20_000
    }
}
