package org.example.kotlineditor.model

import kotlinx.coroutines.flow.Flow

/** One cell of the terminal grid, as drawn on screen. */
data class TerminalCell(
    /** Grapheme of the cell, empty for a blank cell. */
    val text: String = "",
    /** Foreground as `0xRRGGBB`, or [DEFAULT_COLOR] for the terminal default. */
    val foreground: Int = DEFAULT_COLOR,
    /** Background as `0xRRGGBB`, or [DEFAULT_COLOR] for the terminal default. */
    val background: Int = DEFAULT_COLOR,
    /** Combination of the [TerminalAttributes] flags. */
    val attributes: Int = 0,
) {
    /** Whether this cell draws nothing: the renderer can skip it. */
    val isBlank: Boolean
        get() = text.isEmpty() && background == DEFAULT_COLOR && attributes == 0

    /** Defaults shared by every cell. */
    companion object {
        /** Color value meaning "use the terminal's default". */
        const val DEFAULT_COLOR = -1

        /** The blank cell, used to pad rows shorter than the grid. */
        val BLANK = TerminalCell()
    }
}

/** Bit flags of [TerminalCell.attributes]. */
object TerminalAttributes {
    /** Bold or increased intensity. */
    const val BOLD = 1

    /** Italic. */
    const val ITALIC = 2

    /** Underlined. */
    const val UNDERLINE = 4

    /** Struck through. */
    const val STRIKETHROUGH = 8

    /** Dim, drawn with reduced opacity. */
    const val FAINT = 16

    /** Blinking text. */
    const val BLINK = 32

    /** Invisible text: the cell occupies space but draws nothing. */
    const val INVISIBLE = 64

    /** Overlined. */
    const val OVERLINE = 128
}

/**
 * What the server sees of the terminal at one instant: the visible grid, the cursor, the title and the
 * scroll position. Snapshots are complete states, so a client that missed some can just take the next one.
 */
data class TerminalSnapshot(
    /** Width of the grid in cells. */
    val columns: Int,
    /** Height of the grid in cells. */
    val rows: Int,
    /** Cursor column, from the left. */
    val cursorColumn: Int = 0,
    /** Cursor row, from the top of the viewport. */
    val cursorRow: Int = 0,
    /** Whether the cursor is shown (some full-screen programs hide it). */
    val cursorVisible: Boolean = true,
    /** Whether the cursor blinks. */
    val cursorBlinking: Boolean = true,
    /** Title set by the shell or the running program (OSC 0/2), empty when none. */
    val title: String = "",
    /** Number of rows kept in the terminal, including the scrollback. */
    val totalRows: Long = 0,
    /** How many rows the view is scrolled up from the bottom; 0 shows the live output. */
    val scrollOffset: Long = 0,
    /** Whether the process behind the terminal is still running. */
    val running: Boolean = true,
    /** Exit code of the process, `null` while it runs or when it was killed. */
    val exitCode: Int? = null,
    /** Rows of the viewport, top to bottom; trailing blank cells may be omitted. */
    val lines: List<List<TerminalCell>> = emptyList(),
)

/** Row [row] of the viewport, padded with blank cells to [TerminalSnapshot.columns]. */
fun TerminalSnapshot.line(row: Int): List<TerminalCell> {
    val cells = lines.getOrNull(row).orEmpty()
    if (cells.size >= columns) return cells
    return cells + List(columns - cells.size) { TerminalCell.BLANK }
}

/** Text of the viewport, one string per row. */
fun TerminalSnapshot.text(): List<String> =
    (0 until rows).map { row -> line(row).joinToString("") { it.text.ifEmpty { " " } } }

/** Keys the client can send; the server turns them into the bytes the terminal expects. */
enum class TerminalKey {
    /** A character with no named key, sent through [TerminalKeyEvent.text]. */
    Unidentified,

    /** The `A` key. */
    A,

    /** The `B` key. */
    B,

    /** The `C` key. */
    C,

    /** The `D` key. */
    D,

    /** The `E` key. */
    E,

    /** The `F` key. */
    F,

    /** The `G` key. */
    G,

    /** The `H` key. */
    H,

    /** The `I` key. */
    I,

    /** The `J` key. */
    J,

    /** The `K` key. */
    K,

    /** The `L` key. */
    L,

    /** The `M` key. */
    M,

    /** The `N` key. */
    N,

    /** The `O` key. */
    O,

    /** The `P` key. */
    P,

    /** The `Q` key. */
    Q,

    /** The `R` key. */
    R,

    /** The `S` key. */
    S,

    /** The `T` key. */
    T,

    /** The `U` key. */
    U,

    /** The `V` key. */
    V,

    /** The `W` key. */
    W,

    /** The `X` key. */
    X,

    /** The `Y` key. */
    Y,

    /** The `Z` key. */
    Z,

    /** The `0` key, on the main row or the numeric keypad. */
    Digit0,

    /** The `1` key, on the main row or the numeric keypad. */
    Digit1,

    /** The `2` key, on the main row or the numeric keypad. */
    Digit2,

    /** The `3` key, on the main row or the numeric keypad. */
    Digit3,

    /** The `4` key, on the main row or the numeric keypad. */
    Digit4,

    /** The `5` key, on the main row or the numeric keypad. */
    Digit5,

    /** The `6` key, on the main row or the numeric keypad. */
    Digit6,

    /** The `7` key, on the main row or the numeric keypad. */
    Digit7,

    /** The `8` key, on the main row or the numeric keypad. */
    Digit8,

    /** The `9` key, on the main row or the numeric keypad. */
    Digit9,

    /** Enter, on the main block or the numeric keypad. */
    Enter,

    /** Tab. */
    Tab,

    /** Backspace. */
    Backspace,

    /** Escape. */
    Escape,

    /** Space. */
    Space,

    /** Cursor up. */
    ArrowUp,

    /** Cursor down. */
    ArrowDown,

    /** Cursor left. */
    ArrowLeft,

    /** Cursor right. */
    ArrowRight,

    /** Home. */
    Home,

    /** End. */
    End,

    /** Page up. */
    PageUp,

    /** Page down. */
    PageDown,

    /** Insert. */
    Insert,

    /** Delete. */
    Delete,

    /** Function key F1. */
    F1,

    /** Function key F2. */
    F2,

    /** Function key F3. */
    F3,

    /** Function key F4. */
    F4,

    /** Function key F5. */
    F5,

    /** Function key F6. */
    F6,

    /** Function key F7. */
    F7,

    /** Function key F8. */
    F8,

    /** Function key F9. */
    F9,

    /** Function key F10. */
    F10,

    /** Function key F11. */
    F11,

    /** Function key F12. */
    F12,

    /** The minus key. */
    Minus,

    /** The equals key. */
    Equal,

    /** The left bracket key. */
    BracketLeft,

    /** The right bracket key. */
    BracketRight,

    /** The backslash key. */
    Backslash,

    /** The semicolon key. */
    Semicolon,

    /** The apostrophe key. */
    Quote,

    /** The comma key. */
    Comma,

    /** The period key. */
    Period,

    /** The slash key. */
    Slash,

    /** The backquote key. */
    Backquote,
}

/** A key press, release or repeat, with the text it produced when it is a character. */
data class TerminalKeyEvent(
    /** Which key was pressed. */
    val key: TerminalKey,
    /** Text produced by the key, empty for keys that produce none. */
    val text: String = "",
    /** Whether Shift was held. */
    val shift: Boolean = false,
    /** Whether Control was held. */
    val control: Boolean = false,
    /** Whether Alt was held. */
    val alt: Boolean = false,
    /** Whether the platform's Meta/Command/Windows key was held. */
    val meta: Boolean = false,
    /** Whether this is an auto-repeat. */
    val repeat: Boolean = false,
)

/** A terminal running on the server: its screen, and the bytes and keys it accepts. */
interface TerminalProcess {
    /** Complete states of the screen, most recent last; completes when the process exits. */
    val output: Flow<TerminalSnapshot>

    /** Writes raw bytes to the process (paste, escape sequences). */
    fun write(bytes: ByteArray)

    /** Sends a key, encoded by the terminal so that Kitty keyboard and `modifyOtherKeys` are honoured. */
    fun sendKey(event: TerminalKeyEvent)

    /** Pastes [text], wrapped in bracketed-paste markers when the program asked for them. */
    fun paste(text: String)

    /** Tells the terminal and the process its new size. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    /** Scrolls the viewport by [delta] rows, positive going up into the scrollback. */
    fun scroll(delta: Int)

    /** Clears the screen and the scrollback, keeping the line being typed. */
    fun clear()

    /** Stops the process and its children. */
    fun destroy()
}

/** Starts terminals. */
fun interface TerminalLauncher {
    /** Starts a shell in [directory] with the given grid size and cell size in pixels. */
    fun start(directory: String, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): TerminalProcess
}
