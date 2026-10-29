package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.backspace
import org.example.kotlineditor.state.clearSelection
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.deleteLines
import org.example.kotlineditor.state.duplicate
import org.example.kotlineditor.state.extendSelection
import org.example.kotlineditor.state.foldAt
import org.example.kotlineditor.state.indent
import org.example.kotlineditor.state.insert
import org.example.kotlineditor.state.moveEnd
import org.example.kotlineditor.state.moveHome
import org.example.kotlineditor.state.moveLeft
import org.example.kotlineditor.state.moveLines
import org.example.kotlineditor.state.moveRight
import org.example.kotlineditor.state.moveToDocumentEnd
import org.example.kotlineditor.state.moveToDocumentStart
import org.example.kotlineditor.state.newLine
import org.example.kotlineditor.state.selectAll
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.state.shrinkSelection
import org.example.kotlineditor.state.toggleLineComment
import org.example.kotlineditor.state.typeChar
import org.example.kotlineditor.state.unfoldAt
import java.awt.event.KeyEvent as AwtKeyEvent

private const val DELETE_CHAR = 0x7f

/** What an editor shortcut acts on: the document, and the page size in rows. */
private class EditorKeys(val document: Document, val page: Int)

private fun bind(
    keys: Set<Key>,
    ctrl: Boolean? = false,
    shift: Boolean? = null,
    alt: Boolean? = false,
    action: EditorKeys.(Modifiers) -> Unit,
) = binding(keys, ctrl, shift, alt, action)

// The editor keymap (IntelliJ defaults), first match wins.
private val BINDINGS =
    listOf(
        bind(setOf(Key.A), ctrl = true) { document.selectAll() },
        bind(setOf(Key.C), ctrl = true) { copy(document, cut = false) },
        bind(setOf(Key.X), ctrl = true) { copy(document, cut = true) },
        bind(
            setOf(Key.V),
            ctrl = true,
        ) { TextClipboard.get()?.let { text -> document.insert(text.replace("\r\n", "\n")) } },
        bind(setOf(Key.Z), ctrl = true, shift = true) { document.redo() },
        bind(setOf(Key.Z), ctrl = true, shift = false) { document.undo() },
        bind(setOf(Key.D), ctrl = true) { document.duplicate() },
        bind(setOf(Key.W), ctrl = true, shift = true) { document.shrinkSelection() },
        bind(setOf(Key.W), ctrl = true, shift = false) { document.extendSelection() },
        bind(setOf(Key.Y), ctrl = true) { document.deleteLines() },
        bind(setOf(Key.Slash, Key.NumPadDivide), ctrl = true) { document.toggleLineComment() },
        bind(setOf(Key.DirectionUp), alt = true, shift = true) { document.moveLines(-1) },
        bind(setOf(Key.DirectionDown), alt = true, shift = true) { document.moveLines(1) },
        bind(setOf(Key.Minus, Key.NumPadSubtract), ctrl = true, shift = true) { document.folding.foldAll() },
        bind(
            setOf(Key.Minus, Key.NumPadSubtract),
            ctrl = true,
            shift = false,
        ) { document.folding.foldAt(document.caret.line) },
        bind(setOf(Key.Equals, Key.Plus, Key.NumPadAdd), ctrl = true, shift = true) { document.folding.unfoldAll() },
        bind(
            setOf(Key.Equals, Key.Plus, Key.NumPadAdd),
            ctrl = true,
            shift = false,
        ) { document.folding.unfoldAt(document.caret.line) },
        bind(setOf(Key.MoveHome), ctrl = true) { document.moveToDocumentStart(it.shift) },
        bind(setOf(Key.MoveEnd), ctrl = true) { document.moveToDocumentEnd(it.shift) },
        bind(setOf(Key.MoveHome)) { document.moveHome(it.shift) },
        bind(setOf(Key.MoveEnd)) { document.moveEnd(it.shift) },
        bind(setOf(Key.Enter, Key.NumPadEnter)) { document.newLine() },
        bind(setOf(Key.Backspace), ctrl = null) { document.backspace(word = it.ctrl) },
        bind(setOf(Key.Delete), ctrl = null) { document.delete(word = it.ctrl) },
        bind(setOf(Key.Tab)) { document.indent(outdent = it.shift) },
        bind(setOf(Key.DirectionLeft), ctrl = null, alt = null) { document.moveLeft(it.shift, word = it.ctrl) },
        bind(setOf(Key.DirectionRight), ctrl = null, alt = null) { document.moveRight(it.shift, word = it.ctrl) },
        bind(setOf(Key.DirectionUp)) { document.moveByRows(-1, it.shift) },
        bind(setOf(Key.DirectionDown)) { document.moveByRows(1, it.shift) },
        bind(setOf(Key.PageUp)) { document.moveByRows(-page, it.shift) },
        bind(setOf(Key.PageDown)) { document.moveByRows(page, it.shift) },
        bind(setOf(Key.Escape)) { document.clearSelection() },
    )

/** Handles a key event of the editor; returns whether it was used. [page] is the number of visible rows. */
internal fun handleEditorKey(event: KeyEvent, document: Document, page: Int): Boolean {
    val awt = event.nativeKeyEvent as? AwtKeyEvent
    return when {
        // Real AWT: printable characters arrive as KEY_TYPED (this is what makes dead keys and AltGr work).
        awt?.id == AwtKeyEvent.KEY_TYPED -> typed(awt, document)

        event.type != KeyEventType.KeyDown -> false

        shortcut(event, document, page) -> true

        // Synthetic events (UI tests) carry the character on the key press itself.
        else -> awt == null && synthetic(event, document)
    }
}

private fun shortcut(event: KeyEvent, document: Document, page: Int): Boolean =
    BINDINGS.dispatch(event, EditorKeys(document, page))

private fun typed(awt: AwtKeyEvent, document: Document): Boolean {
    val c = awt.keyChar
    val ctrlOnly = awt.isControlDown && !awt.isAltDown
    val accepted = c >= ' ' && c.code != DELETE_CHAR && !ctrlOnly && !awt.isMetaDown
    if (accepted) document.typeChar(c)
    return accepted
}

private fun synthetic(event: KeyEvent, document: Document): Boolean {
    val modified = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed
    val code = if (event.key == Key.Spacebar) ' '.code else event.utf16CodePoint
    val accepted = !modified && code >= ' '.code && code != DELETE_CHAR
    if (accepted) document.typeChar(Char(code))
    return accepted
}

// Vertical caret move counted in displayed rows, so folded regions are skipped.
private fun Document.moveByRows(delta: Int, extend: Boolean) {
    val rows = folding.rowCount()
    moveVerticallyTo(folding.lineAt((folding.rowOf(caret.line) + delta).coerceIn(0, rows - 1)), extend)
}

// IntelliJ: copy and cut without a selection act on the whole line.
private fun copy(document: Document, cut: Boolean) {
    val whole = !document.hasSelection
    TextClipboard.set(if (whole) document.lines[document.caret.line] + "\n" else document.selectedText())
    when {
        !cut -> Unit
        whole -> document.deleteLines()
        else -> document.insert("")
    }
}
