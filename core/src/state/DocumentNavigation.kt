package org.example.kotlineditor.state

// Caret moves and selections.

fun Document.clearSelection() = moveTo(caret)

fun Document.selectAll() = select(TextPos(0, 0), TextPos(lines.lastIndex, lines.last().length))

fun Document.moveLeft(extend: Boolean = false, word: Boolean = false) {
    val (l, c) = caret
    val range = selectionRange()
    val target =
        when {
            !extend && range != null -> range.first
            c > 0 -> TextPos(l, if (word) previousWordBoundary(lines[l], c) else c - 1)
            l > 0 -> TextPos(l - 1, lines[l - 1].length)
            else -> caret
        }
    moveTo(target, extend)
}

fun Document.moveRight(extend: Boolean = false, word: Boolean = false) {
    val (l, c) = caret
    val range = selectionRange()
    val target =
        when {
            !extend && range != null -> range.second
            c < lines[l].length -> TextPos(l, if (word) nextWordBoundary(lines[l], c) else c + 1)
            l < lines.lastIndex -> TextPos(l + 1, 0)
            else -> caret
        }
    moveTo(target, extend)
}

fun Document.moveVertically(delta: Int, extend: Boolean = false) = moveVerticallyTo(caret.line + delta, extend)

/** Home: first non-blank char, then column 0 (IntelliJ "smart home"). */
fun Document.moveHome(extend: Boolean = false) {
    val line = lines[caret.line]
    val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
    moveTo(TextPos(caret.line, if (caret.column == indent) 0 else indent), extend)
}

fun Document.moveEnd(extend: Boolean = false) = moveTo(TextPos(caret.line, lines[caret.line].length), extend)

fun Document.moveToDocumentStart(extend: Boolean = false) = moveTo(TextPos(0, 0), extend)

fun Document.moveToDocumentEnd(extend: Boolean = false) = moveTo(TextPos(lines.lastIndex, lines.last().length), extend)

/** Selects the word around [pos] (double click). */
fun Document.selectWordAt(pos: TextPos) {
    val p = clamp(pos)
    val word = wordAround(lines[p.line], p.column)
    select(TextPos(p.line, word.first), TextPos(p.line, word.last + 1))
}

/** The identifier around [pos], or `null` (used to highlight its other occurrences). */
fun Document.identifierAt(pos: TextPos): String? {
    val line = lines.getOrNull(pos.line).orEmpty()
    val word = line.substring(wordAround(line, pos.column))
    return word.takeIf { it.isNotEmpty() && !it[0].isDigit() }
}
