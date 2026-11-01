package org.example.kotlineditor.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.TerminalTab

private const val FIRST_PRINTABLE_CODE_POINT = 0x20
private const val DELETE_CODE_POINT = 0x7f
private const val WHEEL_ROWS = 3

// Text the punctuation keys produce on a US layout, used when the platform gives no typed character.
private val PUNCTUATION_TEXT =
    mapOf(
        TerminalKey.Space to " ",
        TerminalKey.Minus to "-",
        TerminalKey.Equal to "=",
        TerminalKey.BracketLeft to "[",
        TerminalKey.BracketRight to "]",
        TerminalKey.Backslash to "\\",
        TerminalKey.Semicolon to ";",
        TerminalKey.Quote to "'",
        TerminalKey.Comma to ",",
        TerminalKey.Period to ".",
        TerminalKey.Slash to "/",
        TerminalKey.Backquote to "`",
    )

/** Turns a Compose key event into terminal input; returns whether the terminal used it. */
internal fun handleTerminalKey(
    event: KeyEvent,
    terminal: TerminalState,
    tab: TerminalTab,
    selection: TerminalSelection,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return terminalShortcut(event, terminal, tab, selection) || sendKeyFrom(event, tab)
}

private fun sendKeyFrom(event: KeyEvent, tab: TerminalTab): Boolean {
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val key = terminalKey(event.key)
    val typed = typedText(event, key, ctrl)
    if (key == null && typed.isEmpty()) return false
    tab.sendKey(
        TerminalKeyEvent(
            key = key ?: TerminalKey.Unidentified,
            text = typed,
            shift = event.isShiftPressed,
            control = ctrl,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        ),
    )
    return true
}

private fun terminalShortcut(
    event: KeyEvent,
    terminal: TerminalState,
    tab: TerminalTab,
    selection: TerminalSelection,
): Boolean {
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    val shift = event.isShiftPressed
    return when (event.key) {
        Key.Tab -> {
            val count = terminal.tabs.size
            if (count > 1) terminal.select((terminal.activeIndex + if (shift) count - 1 else 1) % count)
            true
        }

        Key.T -> {
            terminal.newTab()
            true
        }

        Key.W -> {
            terminal.closeActive()
            true
        }

        Key.C -> {
            if (shift) copySelection(tab, selection)
            shift
        }

        Key.V, Key.Insert -> {
            if (shift) TextClipboard.get()?.let(tab::paste)
            shift
        }

        else -> {
            false
        }
    }
}

private fun copySelection(tab: TerminalTab, selection: TerminalSelection) {
    tab.snapshot?.let { snapshot ->
        selectedText(snapshot, selection.anchor, selection.cursor).takeIf { it.isNotEmpty() }?.let(TextClipboard::set)
    }
}

// The text the shell receives for a key press. Ctrl (or Cmd) and plain Alt mark commands through their flags:
// the layout character they would type is never sent as text (Alt+9 is not 'ç' on a French keyboard). Otherwise
// the layout decides, and when the event carries no typed character the US base text of the key stands in.
private fun typedText(event: KeyEvent, key: TerminalKey?, ctrl: Boolean): String =
    when {
        ctrl && !event.isAltPressed -> {
            // The control flags encode the key; its character stays unknown.
            ""
        }

        event.isAltPressed && !ctrl -> {
            // Alt (not AltGr, which reports Ctrl+Alt): an ESC prefix over the base character of the key.
            key?.let { baseText(it, event.isShiftPressed) }.orEmpty()
        }

        else -> {
            printable(event.utf16CodePoint)
                ?: key?.let { baseText(it, event.isShiftPressed) }.orEmpty()
        }
    }

private fun printable(codePoint: Int): String? =
    if (codePoint >= FIRST_PRINTABLE_CODE_POINT && codePoint != DELETE_CODE_POINT) {
        String(Character.toChars(codePoint))
    } else {
        null
    }

private fun baseText(key: TerminalKey, shift: Boolean): String {
    val name = key.name
    return when {
        name.length == 1 && name[0] in 'A'..'Z' -> if (shift) name else name.lowercase()
        name.startsWith("Digit") -> name.removePrefix("Digit")
        else -> PUNCTUATION_TEXT[key].orEmpty()
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun terminalKey(key: Key): TerminalKey? =
    when (key) {
        Key.A -> TerminalKey.A
        Key.B -> TerminalKey.B
        Key.C -> TerminalKey.C
        Key.D -> TerminalKey.D
        Key.E -> TerminalKey.E
        Key.F -> TerminalKey.F
        Key.G -> TerminalKey.G
        Key.H -> TerminalKey.H
        Key.I -> TerminalKey.I
        Key.J -> TerminalKey.J
        Key.K -> TerminalKey.K
        Key.L -> TerminalKey.L
        Key.M -> TerminalKey.M
        Key.N -> TerminalKey.N
        Key.O -> TerminalKey.O
        Key.P -> TerminalKey.P
        Key.Q -> TerminalKey.Q
        Key.R -> TerminalKey.R
        Key.S -> TerminalKey.S
        Key.T -> TerminalKey.T
        Key.U -> TerminalKey.U
        Key.V -> TerminalKey.V
        Key.W -> TerminalKey.W
        Key.X -> TerminalKey.X
        Key.Y -> TerminalKey.Y
        Key.Z -> TerminalKey.Z
        Key.Zero, Key.NumPad0 -> TerminalKey.Digit0
        Key.One, Key.NumPad1 -> TerminalKey.Digit1
        Key.Two, Key.NumPad2 -> TerminalKey.Digit2
        Key.Three, Key.NumPad3 -> TerminalKey.Digit3
        Key.Four, Key.NumPad4 -> TerminalKey.Digit4
        Key.Five, Key.NumPad5 -> TerminalKey.Digit5
        Key.Six, Key.NumPad6 -> TerminalKey.Digit6
        Key.Seven, Key.NumPad7 -> TerminalKey.Digit7
        Key.Eight, Key.NumPad8 -> TerminalKey.Digit8
        Key.Nine, Key.NumPad9 -> TerminalKey.Digit9
        Key.Enter, Key.NumPadEnter -> TerminalKey.Enter
        Key.Tab -> TerminalKey.Tab
        Key.Backspace -> TerminalKey.Backspace
        Key.Escape -> TerminalKey.Escape
        Key.Spacebar -> TerminalKey.Space
        Key.DirectionUp -> TerminalKey.ArrowUp
        Key.DirectionDown -> TerminalKey.ArrowDown
        Key.DirectionLeft -> TerminalKey.ArrowLeft
        Key.DirectionRight -> TerminalKey.ArrowRight
        Key.MoveHome -> TerminalKey.Home
        Key.MoveEnd -> TerminalKey.End
        Key.PageUp -> TerminalKey.PageUp
        Key.PageDown -> TerminalKey.PageDown
        Key.Insert -> TerminalKey.Insert
        Key.Delete -> TerminalKey.Delete
        Key.F1 -> TerminalKey.F1
        Key.F2 -> TerminalKey.F2
        Key.F3 -> TerminalKey.F3
        Key.F4 -> TerminalKey.F4
        Key.F5 -> TerminalKey.F5
        Key.F6 -> TerminalKey.F6
        Key.F7 -> TerminalKey.F7
        Key.F8 -> TerminalKey.F8
        Key.F9 -> TerminalKey.F9
        Key.F10 -> TerminalKey.F10
        Key.F11 -> TerminalKey.F11
        Key.F12 -> TerminalKey.F12
        Key.Minus -> TerminalKey.Minus
        Key.Equals -> TerminalKey.Equal
        Key.LeftBracket -> TerminalKey.BracketLeft
        Key.RightBracket -> TerminalKey.BracketRight
        Key.Backslash -> TerminalKey.Backslash
        Key.Semicolon -> TerminalKey.Semicolon
        Key.Apostrophe -> TerminalKey.Quote
        Key.Comma -> TerminalKey.Comma
        Key.Period -> TerminalKey.Period
        Key.Slash -> TerminalKey.Slash
        Key.Grave -> TerminalKey.Backquote
        else -> null
    }

/** Focus, drag selection and wheel scrolling of the grid. */
internal fun Modifier.terminalPointerInput(
    tab: TerminalTab,
    focus: FocusRequester,
    metrics: CellMetrics,
    selection: TerminalSelection,
): Modifier =
    pointerInput(tab) { detectTapGestures { runCatching { focus.requestFocus() } } }
        .pointerInput(tab) { terminalMouseWheel(tab) }
        .pointerInput(tab, metrics) {
            detectDragGestures(
                onDragStart = { at -> selection.start(cellAt(at, metrics)) },
                onDrag = { change, _ ->
                    selection.moveTo(cellAt(change.position, metrics))
                    change.consume()
                },
            )
        }

private suspend fun PointerInputScope.terminalMouseWheel(tab: TerminalTab) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                val delta =
                    event.changes
                        .firstOrNull()
                        ?.scrollDelta
                        ?.y
                        ?: 0f
                if (delta != 0f) {
                    tab.scroll(if (delta < 0f) WHEEL_ROWS else -WHEEL_ROWS)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

internal fun Modifier.terminalTextInput(tab: TerminalTab): Modifier =
    semantics {
        setText { text ->
            tab.write(text.text)
            true
        }
        insertTextAtCursor { text ->
            tab.write(text.text)
            true
        }
    }
