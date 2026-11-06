package org.example.kotlineditor.state

import org.example.kotlineditor.model.TextChange

/**
 * Where the edits made so far at the carets moved the positions after them, those of the carets not done yet: one
 * shift of lines, and of columns on the line where the last edit ended, so that each caret moves once whatever the
 * number of edits before it (thousands of carets). Positions are given in the text before the edits ("original"); one
 * inside an edited range moves after it.
 */
internal class EditShift {
    private var lineDelta = 0
    private var columnDelta = 0

    // Original line where the last edit ended, whose columns after it moved by [columnDelta].
    private var line = -1

    // Original position of the end of the furthest edit.
    private var frontier: TextPos? = null

    fun apply(caret: Caret): Caret {
        if (frontier == null) return caret
        return Caret(apply(caret.pos), caret.anchor?.let(::apply))
    }

    private fun apply(pos: TextPos): TextPos {
        val edge = frontier
        val from = if (edge != null && pos < edge) edge else pos
        val column = if (from.line == line) from.column + columnDelta else from.column
        return TextPos(from.line + lineDelta, column)
    }

    // Inverse of [apply], for a position after every edit.
    private fun original(pos: TextPos): TextPos {
        val originalLine = pos.line - lineDelta
        val column = if (originalLine == line) pos.column - columnDelta else pos.column
        return TextPos(originalLine, column)
    }

    fun record(change: TextChange) {
        val start = TextPos(change.startLine, change.startColumn)
        val end = TextPos(change.endLine, change.endColumn)
        val newEnd = endOf(start, change.text)
        val originalEnd = original(end)
        val sameLine = originalEnd.line == line
        columnDelta = (if (sameLine) columnDelta else 0) + newEnd.column - end.column
        lineDelta += newEnd.line - end.line
        line = originalEnd.line
        val edge = frontier
        frontier = if (edge == null || originalEnd > edge) originalEnd else edge
    }
}
