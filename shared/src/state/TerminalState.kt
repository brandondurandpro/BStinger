package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalLauncher
import org.example.kotlineditor.model.TerminalProcess
import org.example.kotlineditor.model.TerminalSnapshot

// Grid used until the panel has been measured, so the shell has a sane size when it starts.
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val DEFAULT_CELL_WIDTH = 8
private const val DEFAULT_CELL_HEIGHT = 16

/**
 * One terminal tab: a shell running on the server and the last screen it sent.
 *
 * Every snapshot is a complete screen, so the tab only keeps the latest one; the emulator owns the scrollback
 * and re-sends the viewport when the user scrolls.
 */
@Stable
class TerminalTab(
    /** Stable id of the tab, used for its key in the UI and its test tag. */
    val number: Int,
    /** Short name shown on the tab, like IntelliJ's: "Local", then "Local (2)" and so on. */
    val name: String,
    private val process: TerminalProcess,
    scope: CoroutineScope,
) {
    /** The last complete screen, or `null` before the first one arrives. */
    var snapshot by mutableStateOf<TerminalSnapshot?>(null)
        private set

    /**
     * Title the running program set (OSC 0/2), such as `user@host: ~/project` or `C:\Windows\system32\cmd.exe`, or
     * the [name] while it sets none. Too long for the tab, it is shown in the tab's tooltip.
     */
    var title by mutableStateOf(name)
        private set

    /**
     * Whether the server lost the shell instead of the shell exiting: its screens stopped while it was running, as
     * when the server restarts. The tab then only shows its last screen; typing there would go nowhere.
     */
    var lost by mutableStateOf(false)
        private set

    /** Whether the shell is still running. */
    val running: Boolean get() = !lost && snapshot?.running ?: true

    // Last size sent to the server, to avoid resizing on every frame.
    private var columns = DEFAULT_COLUMNS
    private var rows = DEFAULT_ROWS
    private var cellWidth = DEFAULT_CELL_WIDTH
    private var cellHeight = DEFAULT_CELL_HEIGHT

    private val job: Job =
        scope.launch {
            process.output.collect { screen ->
                snapshot = screen
                if (screen.title.isNotBlank()) title = screen.title
            }
            // An exiting shell sends a last screen saying it stopped; a lost one just stops sending.
            if (snapshot?.running != false) lost = true
        }

    /** Sends the characters the user typed, as if they came from the keyboard. */
    fun write(text: String) {
        if (text.isNotEmpty()) process.write(text.encodeToByteArray())
    }

    /** Sends a key, encoded by the terminal so that the running program gets what it expects. */
    fun sendKey(event: TerminalKeyEvent) = process.sendKey(event)

    /** Pastes [text], wrapped in bracketed-paste markers when the program asked for them. */
    fun paste(text: String) {
        if (text.isNotEmpty()) process.paste(text)
    }

    /** Applies the grid the panel can display; a no-op when nothing changed. */
    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (hasSize(columns, rows, cellWidth, cellHeight)) return
        this.columns = columns
        this.rows = rows
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        process.resize(columns, rows, cellWidth, cellHeight)
    }

    private fun hasSize(columns: Int, rows: Int, width: Int, height: Int): Boolean {
        val sameGrid = columns == this.columns && rows == this.rows
        val sameCell = width == cellWidth && height == cellHeight
        return sameGrid && sameCell
    }

    /** Scrolls the viewport by [delta] rows, positive going up into the scrollback. */
    fun scroll(delta: Int) {
        if (delta != 0) process.scroll(delta)
    }

    /** Stops the shell and the collector. */
    fun dispose() {
        job.cancel()
        process.destroy()
    }
}

/**
 * The Terminal tool window: tabs, each with its own shell. Tabs are started the first time the window is shown
 * and keep running while it is hidden, like IntelliJ's terminal.
 */
@Stable
class TerminalState(
    private val directory: String,
    private val launcher: TerminalLauncher,
    private val scope: CoroutineScope,
) {
    /** Tabs of the project, in display order. */
    val tabs = mutableStateListOf<TerminalTab>()

    /** Index of the visible tab. */
    var activeIndex by mutableStateOf(0)
        private set

    /** Whether the keyboard goes to the active terminal (set by the panel). */
    var focused by mutableStateOf(false)

    /** Why the last terminal could not start, if it could not. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The visible tab, if there is one. */
    val active: TerminalTab? get() = tabs.getOrNull(activeIndex)

    private var nextNumber = 1

    /** Starts the first tab the first time the window is shown. */
    fun ensureStarted() {
        if (tabs.isEmpty()) newTab()
    }

    /** Opens a new tab and shows it. */
    fun newTab() {
        val process =
            runCatching {
                launcher.start(directory, DEFAULT_COLUMNS, DEFAULT_ROWS, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT)
            }.getOrElse {
                error = "Cannot start a terminal: ${it.message}"
                return
            }
        error = null
        tabs += TerminalTab(nextNumber++, nextName(), process, scope)
        activeIndex = tabs.lastIndex
        focused = true
    }

    // The first free name: "Local", then "Local (2)", "Local (3)"…; a closed tab's name is given again.
    private fun nextName(): String {
        val taken = tabs.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map(::tabName).first { it !in taken }
    }

    /** Shows the tab at [index]. */
    fun select(index: Int) {
        if (index in tabs.indices) {
            activeIndex = index
            focused = true
        }
    }

    /** Closes the visible tab, stopping its shell. */
    fun closeActive() = close(activeIndex)

    /** Closes the tab at [index]. */
    fun close(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.dispose()
        tabs.removeAt(index)
        activeIndex =
            when {
                tabs.isEmpty() -> 0
                index < activeIndex -> activeIndex - 1
                else -> activeIndex.coerceAtMost(tabs.lastIndex)
            }
    }

    /** Closes the tabs whose index matches [closes], keeping the one at [index] and showing it. */
    fun closeAround(index: Int, closes: (Int) -> Boolean) {
        val kept = tabs.getOrNull(index) ?: return
        val closed = tabs.filterIndexed { i, _ -> closes(i) }
        closed.forEach { it.dispose() }
        tabs.removeAll(closed)
        activeIndex = tabs.indexOf(kept)
        focused = true
    }

    /** Closes every tab, stopping their shells. */
    fun closeAll() {
        tabs.forEach { it.dispose() }
        tabs.clear()
        activeIndex = 0
    }

    /** Stops every shell; called when the project closes. */
    fun stop() {
        closeAll()
        focused = false
    }
}

/** Name of the [index]-th terminal tab (one-based), as IntelliJ names its local terminals. */
fun tabName(index: Int): String = if (index == 1) "Local" else "Local ($index)"
