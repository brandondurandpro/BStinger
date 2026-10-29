package org.example.kotlineditor.state

// Actions on whole lines, and the Ctrl+W selection steps.

private typealias Range = Pair<TextPos, TextPos>

/** First and last lines touched by the caret or the selection (a selection ending at column 0 stops before). */
internal fun Document.selectedLineRange(): Pair<Int, Int> {
    val (start, end) = selectionRange() ?: Pair(caret, caret)
    val endsAtLineStart = end.column == 0 && end.line > start.line
    return start.line to if (endsAtLineStart) end.line - 1 else end.line
}

/** Ctrl+/: toggles line comments on the touched lines. */
fun Document.toggleLineComment() {
    val prefix =
        (
            if (language.slashComments) {
                "//"
            } else if (language.hashComments) {
                "#"
            } else {
                null
            }
            ) ?: return
    val (first, last) = selectedLineRange()
    val touched = (first..last).filter { lines[it].isNotBlank() }.ifEmpty { listOf(first) }
    val uncommenting = touched.all { lines[it].trimStart().startsWith(prefix) }
    val newLines =
        (first..last).map { i ->
            when {
                uncommenting -> uncomment(lines[i], prefix)
                i in touched -> "$prefix ${lines[i]}"
                else -> lines[i]
            }
        }
    val caretBefore = caret
    val oldLength = lines[caretBefore.line].length
    val hadSelection = hasSelection
    replace(TextPos(first, 0), TextPos(last, lines[last].length), newLines.joinToString("\n"))
    if (hadSelection) {
        select(TextPos(first, 0), TextPos(last, lines[last].length))
    } else {
        val shift = lines[caretBefore.line].length - oldLength
        moveTo(TextPos(caretBefore.line, (caretBefore.column + shift).coerceAtLeast(0)))
        moveVertically(1)
    }
}

private fun uncomment(line: String, prefix: String): String {
    val at = line.indexOf(prefix)
    val rest = if (at < 0) line else line.removeRange(at, at + prefix.length)
    return if (at >= 0 && rest.getOrNull(at) == ' ') rest.removeRange(at, at + 1) else rest
}

/** Alt+Shift+Up/Down: moves the lines touched by the caret or the selection, keeping them selected. */
fun Document.moveLines(delta: Int) {
    val (first, last) = selectedLineRange()
    val target = if (delta < 0) first - 1 else last + 1
    if (target !in lines.indices) return
    val block = (first..last).map { lines[it] }
    val anchorBefore = anchor
    val caretBefore = caret
    val newLines = if (delta < 0) block + lines[target] else listOf(lines[target]) + block
    val from = minOf(first, target)
    val to = maxOf(last, target)
    replace(TextPos(from, 0), TextPos(to, lines[to].length), newLines.joinToString("\n"))
    val shift = if (delta < 0) -1 else 1
    val newCaret = TextPos(caretBefore.line + shift, caretBefore.column)
    if (anchorBefore == null) {
        moveTo(newCaret)
    } else {
        select(TextPos(anchorBefore.line + shift, anchorBefore.column), newCaret)
    }
}

/** Tab / Shift+Tab: indents or unindents the selected lines, inserts spaces otherwise. */
fun Document.indent(outdent: Boolean = false) {
    if (!outdent && !hasSelection) {
        return insert(" ".repeat(TAB_SIZE - visualColumn(lines[caret.line], caret.column) % TAB_SIZE))
    }
    val (first, last) = selectedLineRange()
    val newLines =
        (first..last).map { i ->
            val line = lines[i]
            when {
                !outdent -> " ".repeat(TAB_SIZE) + line
                line.startsWith('\t') -> line.substring(1)
                else -> line.substring(minOf(TAB_SIZE, line.takeWhile { it == ' ' }.length))
            }
        }
    replace(TextPos(first, 0), TextPos(last, lines[last].length), newLines.joinToString("\n"))
    select(TextPos(first, 0), TextPos(last, lines[last].length))
}

/**
 * Ctrl+W: grows the selection to the next enclosing unit: word, line text, enclosing block (from the
 * foldable regions), then the whole document. Ctrl+Shift+W walks back through the same steps.
 */
fun Document.extendSelection() {
    val current = selectionRange()
    val next = nextSelection(current)
    if (next != current) {
        selectionHistory.addLast(anchor to caret)
        select(next.first, next.second)
    }
}

fun Document.shrinkSelection() {
    val (previousAnchor, previousCaret) = selectionHistory.removeLastOrNull() ?: return
    if (previousAnchor == null) moveTo(previousCaret) else select(previousAnchor, previousCaret)
}

private fun Document.nextSelection(current: Range?): Range {
    val word = wordAround(lines[caret.line], caret.column)
    return when {
        current == null && !word.isEmpty() -> TextPos(caret.line, word.first) to TextPos(caret.line, word.last + 1)
        current == null -> lineContent(caret.line)
        current.strictlyInside(lineContent(current.first.line)) -> lineContent(current.first.line)
        else -> enclosingBlock(current) ?: Pair(TextPos(0, 0), TextPos(lines.lastIndex, lines.last().length))
    }
}

private fun Range.strictlyInside(other: Range): Boolean =
    this != other && other.first <= first && other.second >= second

private fun Document.enclosingBlock(current: Range): Range? =
    folding.regions.entries
        .filter { (start, end) -> start <= current.first.line && end >= current.second.line }
        .map { (start, end) -> TextPos(start, 0) to TextPos(end, lines[end].length) }
        .filter { it != current && it.first <= current.first && it.second >= current.second }
        .minByOrNull { (s, e) -> e.line - s.line }

private fun Document.lineContent(line: Int): Range {
    val text = lines[line]
    val start = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return TextPos(line, start) to TextPos(line, maxOf(start, end))
}
