package org.example.kotlineditor.unit

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalProcess
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.TerminalTab
import org.example.kotlineditor.ui.CellPos
import org.example.kotlineditor.ui.TerminalSelection
import org.example.kotlineditor.ui.TextClipboard
import org.example.kotlineditor.ui.handleTerminalKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What AWT reports for a key that types nothing (CHAR_UNDEFINED).
private const val NO_CHARACTER = 0xFFFF

/** A shell that records what the terminal sends it, and shows [screen] once started. */
private class FakeProcess(screen: TerminalSnapshot?) : TerminalProcess {
    val keys = mutableListOf<TerminalKeyEvent>()
    val pastes = mutableListOf<String>()
    var destroyed = false

    override val output: Flow<TerminalSnapshot> =
        flow {
            if (screen != null) emit(screen)
            awaitCancellation()
        }

    override fun write(bytes: ByteArray) = Unit

    override fun sendKey(event: TerminalKeyEvent) {
        keys += event
    }

    override fun paste(text: String) {
        pastes += text
    }

    override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) = Unit

    override fun scroll(delta: Int) = Unit

    override fun clear() = Unit

    override fun destroy() {
        destroyed = true
    }
}

@OptIn(InternalComposeUiApi::class)
class TerminalInputTest {
    private lateinit var scope: CoroutineScope
    private var systemClipboard = true

    // Shells of the last terminal made, in the order its tabs started them.
    private val processes = mutableListOf<FakeProcess>()

    // The screen the next started shell shows.
    private var screen: TerminalSnapshot? = null

    @BeforeTest
    fun setUp() {
        systemClipboard = TextClipboard.useSystem
        TextClipboard.useSystem = false
        TextClipboard.set("")
        // Unconfined: a tab collects its first screen before newTab returns.
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        TextClipboard.set("")
        TextClipboard.useSystem = systemClipboard
    }

    private fun terminal(tabs: Int = 1): TerminalState {
        processes.clear()
        val launcher = { _: String, _: Int, _: Int, _: Int, _: Int ->
            FakeProcess(screen).also { processes += it }
        }
        val state = TerminalState("/project", launcher, scope)
        repeat(tabs) { state.newTab() }
        return state
    }

    private fun keyDown(
        key: Key,
        codePoint: Int = NO_CHARACTER,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ) = KeyEvent(
        key,
        KeyEventType.KeyDown,
        codePoint = codePoint,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = alt,
        isShiftPressed = shift,
    )

    // Presses [event] on the active tab of [state]; returns whether the terminal used it.
    private fun press(
        event: KeyEvent,
        state: TerminalState = terminal(),
        selection: TerminalSelection = TerminalSelection(),
    ): Boolean {
        val tab: TerminalTab = checkNotNull(state.active) { "no active tab" }
        return handleTerminalKey(event, state, tab, selection)
    }

    // The keys the shell of the active tab received after pressing [event].
    private fun sentFor(event: KeyEvent): List<TerminalKeyEvent> {
        val state = terminal()
        assertTrue(press(event, state), "the terminal ignored $event")
        return activeProcess(state).keys
    }

    private fun activeProcess(state: TerminalState): FakeProcess {
        val index = state.activeIndex
        val live = processes.filterNot { it.destroyed }
        return checkNotNull(live.getOrNull(index)) { "no shell for tab $index" }
    }

    private fun cells(text: String): List<TerminalCell> = text.map { TerminalCell(it.toString()) }

    @Test
    fun `key up is ignored`() {
        val state = terminal()
        val event = KeyEvent(Key.A, KeyEventType.KeyUp, codePoint = 'a'.code)
        assertFalse(press(event, state))
        assertEquals(emptyList(), activeProcess(state).keys)
    }

    @Test
    fun `a printable key sends its text`() {
        val sent = sentFor(keyDown(Key.A, codePoint = 'a'.code))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.A, text = "a")), sent)
    }

    @Test
    fun `Shift reaches the event with the shifted text`() {
        val sent = sentFor(keyDown(Key.A, codePoint = 'A'.code, shift = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.A, text = "A", shift = true)), sent)
    }

    @Test
    fun `Ctrl sets control and sends no text`() {
        val sent = sentFor(keyDown(Key.A, codePoint = 1, ctrl = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.A, control = true)), sent)
    }

    @Test
    fun `Cmd sets control as well as meta`() {
        val sent = sentFor(keyDown(Key.A, codePoint = 'a'.code, meta = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.A, control = true, meta = true)), sent)
    }

    @Test
    fun `Ctrl+Shift on a key that is no shortcut reaches the event with both flags`() {
        val sent = sentFor(keyDown(Key.A, codePoint = 1, ctrl = true, shift = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.A, shift = true, control = true)), sent)
    }

    // Alt+B is 'ƒ' or '∫' on some layouts: the shell expects ESC b, so the base character goes with the flag.
    @Test
    fun `Alt sends the base character of the key with the alt flag`() {
        val sent = sentFor(keyDown(Key.B, codePoint = '∫'.code, alt = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.B, text = "b", alt = true)), sent)
    }

    @Test
    fun `Alt+Shift sends the shifted base character`() {
        val sent = sentFor(keyDown(Key.B, codePoint = 'ı'.code, alt = true, shift = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.B, text = "B", shift = true, alt = true)), sent)
    }

    // Alt+9 is not 'ç' on a French keyboard: the digit goes with the flag.
    @Test
    fun `Alt on a digit sends the digit`() {
        val sent = sentFor(keyDown(Key.Nine, codePoint = 'ç'.code, alt = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Digit9, text = "9", alt = true)), sent)
    }

    @Test
    fun `Ctrl+Alt (AltGr) sends the typed character`() {
        val sent = sentFor(keyDown(Key.Zero, codePoint = '@'.code, ctrl = true, alt = true))
        val expected = TerminalKeyEvent(TerminalKey.Digit0, text = "@", control = true, alt = true)
        assertEquals(listOf(expected), sent)
    }

    @Test
    fun `the layout character wins over the US text of the key`() {
        val sent = sentFor(keyDown(Key.One, codePoint = '&'.code))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Digit1, text = "&")), sent)
    }

    @Test
    fun `a key reported without a character sends its US text`() {
        val sent = sentFor(keyDown(Key.Q))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Q, text = "q")), sent)
    }

    @Test
    fun `numpad digits send the digit keys`() {
        val sent = sentFor(keyDown(Key.NumPad7, codePoint = '7'.code))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Digit7, text = "7")), sent)
    }

    @Test
    fun `keys that type nothing are sent without text`() {
        val cases =
            listOf(
                keyDown(Key.Enter, codePoint = '\r'.code) to TerminalKey.Enter,
                keyDown(Key.NumPadEnter, codePoint = '\r'.code) to TerminalKey.Enter,
                keyDown(Key.Tab, codePoint = '\t'.code) to TerminalKey.Tab,
                keyDown(Key.Backspace, codePoint = '\b'.code) to TerminalKey.Backspace,
                keyDown(Key.Delete, codePoint = 0x7f) to TerminalKey.Delete,
                keyDown(Key.Escape, codePoint = 0x1b) to TerminalKey.Escape,
                keyDown(Key.DirectionUp) to TerminalKey.ArrowUp,
                keyDown(Key.MoveHome) to TerminalKey.Home,
                keyDown(Key.PageDown) to TerminalKey.PageDown,
                keyDown(Key.F5) to TerminalKey.F5,
            )
        for ((event, key) in cases) {
            assertEquals(listOf(TerminalKeyEvent(key)), sentFor(event), "key ${event.key}")
        }
    }

    @Test
    fun `a key without mapping nor text is not consumed`() {
        for (key in listOf(Key.ShiftLeft, Key.CtrlLeft, Key.AltLeft, Key.MetaLeft, Key.CapsLock)) {
            val state = terminal()
            assertFalse(press(keyDown(key), state), "key $key")
            assertEquals(emptyList(), activeProcess(state).keys, "key $key")
        }
    }

    @Test
    fun `a key without mapping that types a character sends it as unidentified`() {
        val sent = sentFor(keyDown(Key.Unknown, codePoint = 'é'.code))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Unidentified, text = "é")), sent)
    }

    @Test
    fun `a character outside the BMP is sent whole`() {
        val emoji = "😀"
        val sent = sentFor(keyDown(Key.Unknown, codePoint = emoji.codePointAt(0)))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Unidentified, text = emoji)), sent)
    }

    @Test
    fun `punctuation keys without a character send their US text`() {
        val punctuation =
            listOf(
                Triple(Key.Spacebar, TerminalKey.Space, " "),
                Triple(Key.Minus, TerminalKey.Minus, "-"),
                Triple(Key.Equals, TerminalKey.Equal, "="),
                Triple(Key.LeftBracket, TerminalKey.BracketLeft, "["),
                Triple(Key.RightBracket, TerminalKey.BracketRight, "]"),
                Triple(Key.Backslash, TerminalKey.Backslash, "\\"),
                Triple(Key.Semicolon, TerminalKey.Semicolon, ";"),
                Triple(Key.Apostrophe, TerminalKey.Quote, "'"),
                Triple(Key.Comma, TerminalKey.Comma, ","),
                Triple(Key.Period, TerminalKey.Period, "."),
                Triple(Key.Slash, TerminalKey.Slash, "/"),
                Triple(Key.Grave, TerminalKey.Backquote, "`"),
            )
        for ((key, terminalKey, text) in punctuation) {
            val sent = sentFor(keyDown(key))
            assertEquals(listOf(TerminalKeyEvent(terminalKey, text = text)), sent, "key $key")
        }
    }

    @Test
    fun `Alt on a punctuation key sends its US text`() {
        val sent = sentFor(keyDown(Key.Period, codePoint = '…'.code, alt = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.Period, text = ".", alt = true)), sent)
    }

    @Test
    fun `Ctrl+C without Shift interrupts the shell instead of copying`() {
        TextClipboard.set("before")
        val sent = sentFor(keyDown(Key.C, codePoint = 3, ctrl = true))
        assertEquals(listOf(TerminalKeyEvent(TerminalKey.C, control = true)), sent)
        assertEquals("before", TextClipboard.get())
    }

    @Test
    fun `Ctrl+Shift+C copies the selection and sends nothing`() {
        screen = TerminalSnapshot(columns = 8, rows = 2, lines = listOf(cells("hello"), cells("world")))
        val state = terminal()
        val selection = TerminalSelection()
        selection.start(CellPos(row = 1, column = 2))
        selection.moveTo(CellPos(row = 0, column = 1))
        assertTrue(press(keyDown(Key.C, ctrl = true, shift = true), state, selection))
        assertEquals("ello\nwor", TextClipboard.get())
        assertEquals(emptyList(), activeProcess(state).keys)
    }

    @Test
    fun `Cmd+Shift+C copies the selection too`() {
        screen = TerminalSnapshot(columns = 8, rows = 1, lines = listOf(cells("echo hi")))
        val state = terminal()
        val selection = TerminalSelection()
        selection.start(CellPos(row = 0, column = 5))
        selection.moveTo(CellPos(row = 0, column = 6))
        assertTrue(press(keyDown(Key.C, meta = true, shift = true), state, selection))
        assertEquals("hi", TextClipboard.get())
    }

    @Test
    fun `Ctrl+Shift+C without a selection keeps the clipboard`() {
        screen = TerminalSnapshot(columns = 8, rows = 1, lines = listOf(cells("hello")))
        TextClipboard.set("before")
        val state = terminal()
        assertTrue(press(keyDown(Key.C, ctrl = true, shift = true), state))
        assertEquals("before", TextClipboard.get())
        assertEquals(emptyList(), activeProcess(state).keys)
    }

    @Test
    fun `Ctrl+Shift+C over blank cells keeps the clipboard`() {
        screen = TerminalSnapshot(columns = 8, rows = 1, lines = listOf(cells("hi")))
        TextClipboard.set("before")
        val state = terminal()
        val selection = TerminalSelection()
        selection.start(CellPos(row = 0, column = 4))
        selection.moveTo(CellPos(row = 0, column = 7))
        assertTrue(press(keyDown(Key.C, ctrl = true, shift = true), state, selection))
        assertEquals("before", TextClipboard.get())
    }

    @Test
    fun `Ctrl+Shift+C before the first screen copies nothing`() {
        TextClipboard.set("before")
        val selection = TerminalSelection()
        selection.start(CellPos(row = 0, column = 0))
        selection.moveTo(CellPos(row = 0, column = 3))
        assertTrue(press(keyDown(Key.C, ctrl = true, shift = true), selection = selection))
        assertEquals("before", TextClipboard.get())
    }

    @Test
    fun `Ctrl+Shift+V and Ctrl+Shift+Insert paste the clipboard`() {
        for (key in listOf(Key.V, Key.Insert)) {
            TextClipboard.set("ls -la")
            val state = terminal()
            assertTrue(press(keyDown(key, ctrl = true, shift = true), state), "key $key")
            val process = activeProcess(state)
            assertEquals(listOf("ls -la"), process.pastes, "key $key")
            assertEquals(emptyList(), process.keys, "key $key")
        }
    }

    @Test
    fun `Ctrl+Shift+V with an empty clipboard pastes nothing`() {
        val state = terminal()
        assertTrue(press(keyDown(Key.V, ctrl = true, shift = true), state))
        val process = activeProcess(state)
        assertEquals(emptyList(), process.pastes)
        assertEquals(emptyList(), process.keys)
    }

    @Test
    fun `Ctrl+V and Ctrl+Insert without Shift go to the shell`() {
        TextClipboard.set("ls -la")
        val state = terminal()
        assertTrue(press(keyDown(Key.V, codePoint = 0x16, ctrl = true), state))
        assertTrue(press(keyDown(Key.Insert, ctrl = true), state))
        val process = activeProcess(state)
        val expected =
            listOf(
                TerminalKeyEvent(TerminalKey.V, control = true),
                TerminalKeyEvent(TerminalKey.Insert, control = true),
            )
        assertEquals(expected, process.keys)
        assertEquals(emptyList(), process.pastes)
    }

    @Test
    fun `Ctrl+T opens a tab and shows it`() {
        val state = terminal()
        assertTrue(press(keyDown(Key.T, ctrl = true), state))
        assertEquals(2, state.tabs.size)
        assertEquals(1, state.activeIndex)
        assertEquals(emptyList(), processes.flatMap { it.keys })
    }

    @Test
    fun `Cmd+T opens a tab too`() {
        val state = terminal()
        assertTrue(press(keyDown(Key.T, meta = true), state))
        assertEquals(2, state.tabs.size)
    }

    @Test
    fun `Ctrl+W closes the active tab and stops its shell`() {
        val state = terminal(tabs = 2)
        val closing = processes[1]
        assertTrue(press(keyDown(Key.W, ctrl = true), state))
        assertEquals(1, state.tabs.size)
        assertTrue(closing.destroyed)
        assertFalse(processes[0].destroyed)
        assertEquals(emptyList(), closing.keys)
    }

    @Test
    fun `Ctrl+Tab and Ctrl+Shift+Tab cycle through the tabs`() {
        val state = terminal(tabs = 3)
        assertEquals(2, state.activeIndex)
        assertTrue(press(keyDown(Key.Tab, ctrl = true), state))
        assertEquals(0, state.activeIndex)
        assertTrue(press(keyDown(Key.Tab, ctrl = true, shift = true), state))
        assertEquals(2, state.activeIndex)
        assertTrue(press(keyDown(Key.Tab, ctrl = true, shift = true), state))
        assertEquals(1, state.activeIndex)
        assertEquals(emptyList(), processes.flatMap { it.keys })
    }

    @Test
    fun `Ctrl+Tab with a single tab is consumed and changes nothing`() {
        val state = terminal()
        assertTrue(press(keyDown(Key.Tab, ctrl = true), state))
        assertEquals(0, state.activeIndex)
        assertEquals(emptyList(), activeProcess(state).keys)
    }

    @Test
    fun `T, W and Tab without Ctrl go to the shell`() {
        val state = terminal()
        assertTrue(press(keyDown(Key.T, codePoint = 't'.code), state))
        assertTrue(press(keyDown(Key.W, codePoint = 'w'.code), state))
        assertTrue(press(keyDown(Key.Tab, codePoint = '\t'.code), state))
        val expected =
            listOf(
                TerminalKeyEvent(TerminalKey.T, text = "t"),
                TerminalKeyEvent(TerminalKey.W, text = "w"),
                TerminalKeyEvent(TerminalKey.Tab),
            )
        assertEquals(expected, activeProcess(state).keys)
        assertEquals(1, state.tabs.size)
    }
}
