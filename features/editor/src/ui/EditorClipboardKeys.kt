package org.example.kotlineditor.ui

import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.clearSelection
import org.example.kotlineditor.state.deleteLines
import org.example.kotlineditor.state.forEachCaret
import org.example.kotlineditor.state.insert
import org.example.kotlineditor.state.removeExtraCarets
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.state.selectedTextOfCarets

// Copy, cut and Escape of the editor keymap, with one caret or several.

// Escape removes the other carets first, then the selection.
internal fun escape(document: Document) {
    if (document.extraCarets.isNotEmpty()) document.removeExtraCarets() else document.clearSelection()
}

// IntelliJ: copy and cut without a selection act on the whole line.
internal fun copy(document: Document, cut: Boolean) {
    if (document.extraCarets.isNotEmpty()) return copyOfCarets(document, cut)
    val whole = !document.hasSelection
    TextClipboard.set(if (whole) document.lines[document.caret.line] + "\n" else document.selectedText())
    when {
        !cut -> Unit
        whole -> document.deleteLines()
        else -> document.insert("")
    }
}

// With several carets: the text of each caret, one per line; cut deletes each selection (or line).
private fun copyOfCarets(document: Document, cut: Boolean) {
    TextClipboard.set(document.selectedTextOfCarets())
    if (!cut) return
    document.forEachCaret { if (hasSelection) insert("") else deleteLines() }
}
