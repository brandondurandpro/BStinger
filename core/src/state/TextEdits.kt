package org.example.kotlineditor.state

import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange

/** Where [pos], a position of the text before [change], is in the text after it; inside the replaced text, its end. */
fun shifted(pos: TextPos, change: TextChange): TextPos {
    val start = TextPos(change.startLine, change.startColumn)
    val end = TextPos(change.endLine, change.endColumn)
    if (pos < start) return pos
    val insertedLines = change.text.split('\n')
    val lastInserted = insertedLines.last()
    val newEndLine = change.startLine + insertedLines.lastIndex
    val newEndColumn = if (insertedLines.size == 1) change.startColumn + lastInserted.length else lastInserted.length
    if (pos < end) return TextPos(newEndLine, newEndColumn)
    val lineDelta = newEndLine - change.endLine
    val column = if (pos.line == change.endLine) newEndColumn + (pos.column - change.endColumn) else pos.column
    return TextPos(pos.line + lineDelta, column)
}

/**
 * [range] after [change]: its ends follow the text; null when the change replaced text inside it with a line break or
 * removed it entirely, as the marked text is gone.
 */
fun shifted(range: TextRange, change: TextChange): TextRange? {
    val start = TextPos(range.startLine, range.startColumn)
    val end = TextPos(range.endLine, range.endColumn)
    val changeStart = TextPos(change.startLine, change.startColumn)
    val changeEnd = TextPos(change.endLine, change.endColumn)
    val covers = changeStart <= start && changeEnd >= end && start != end
    if (covers) return null
    // Typing at the end of a range does not extend it, typing at its start pushes it.
    val newStart = shifted(start, change)
    val newEnd = if (end == changeStart && changeStart == changeEnd) end else shifted(end, change)
    return TextRange(newStart.line, newStart.column, newEnd.line, newEnd.column)
}

/**
 * Applies [edits], all in the coordinates of the current text and not overlapping, as one change and one undo step,
 * keeping the caret where its text went. Edits language servers send (formatting, rename, quick fixes).
 */
fun Document.applyEdits(edits: List<TextChange>) {
    if (edits.isEmpty()) return
    val inside = edits.map { clamped(it, lines) }
    val sorted = inside.sortedWith(compareBy({ it.startLine }, { it.startColumn }, { it.endLine }, { it.endColumn }))
    val first = sorted[0]
    val regionStart = TextPos(first.startLine, first.startColumn)
    val lastEnd = sorted.maxOf { TextPos(it.endLine, it.endColumn) }
    val region = textBetween(regionStart, lastEnd)
    val rewritten = rewrite(region, regionStart, sorted)
    val caretAfter = sorted.asReversed().fold(caret) { pos, edit -> shifted(pos, edit) }
    replace(regionStart, lastEnd, rewritten)
    moveTo(caretAfter)
}

// Servers name positions past the end of the text (the line after the last one): they are its end.
private fun clamped(edit: TextChange, lines: List<String>): TextChange {
    val start = clamped(TextPos(edit.startLine, edit.startColumn), lines)
    val end = clamped(TextPos(edit.endLine, edit.endColumn), lines)
    return TextChange(start.line, start.column, end.line, end.column, edit.text)
}

private fun clamped(pos: TextPos, lines: List<String>): TextPos {
    val last = lines.lastIndex.coerceAtLeast(0)
    if (pos.line > last) return TextPos(last, lines.getOrNull(last)?.length ?: 0)
    val line = pos.line.coerceAtLeast(0)
    val length = lines.getOrNull(line)?.length ?: 0
    return TextPos(line, pos.column.coerceIn(0, length))
}

/** [lines] with [edits] applied (in the coordinates of [lines]): the files that are not open. */
fun applyEdits(lines: List<String>, edits: List<TextChange>): List<String> {
    val text = lines.toMutableList()
    val sorted = edits.sortedWith(compareByDescending<TextChange> { it.startLine }.thenByDescending { it.startColumn })
    for (edit in sorted) {
        val startLine = edit.startLine.coerceIn(0, text.lastIndex.coerceAtLeast(0))
        if (text.isEmpty()) text += ""
        val endLine = edit.endLine.coerceIn(startLine, text.lastIndex)
        val prefix = text[startLine].take(edit.startColumn.coerceIn(0, text[startLine].length))
        val suffix = text[endLine].drop(edit.endColumn.coerceIn(0, text[endLine].length))
        val replacement = (prefix + edit.text + suffix).split('\n')
        text.subList(startLine, endLine + 1).clear()
        text.addAll(startLine, replacement)
    }
    return text
}

// The text of [region] (starting at [origin]) with [edits] applied, the edits sorted by position.
private fun rewrite(region: String, origin: TextPos, edits: List<TextChange>): String {
    val offsets = lineOffsets(region)

    fun offsetOf(line: Int, column: Int): Int {
        val relativeLine = line - origin.line
        val lineStart = offsets.getOrElse(relativeLine) { region.length }
        val columnInRegion = if (relativeLine == 0) column - origin.column else column
        return (lineStart + columnInRegion).coerceIn(0, region.length)
    }
    val builder = StringBuilder()
    var copied = 0
    for (edit in edits) {
        val start = offsetOf(edit.startLine, edit.startColumn).coerceAtLeast(copied)
        val end = offsetOf(edit.endLine, edit.endColumn).coerceAtLeast(start)
        builder.append(region, copied, start)
        builder.append(edit.text)
        copied = end
    }
    builder.append(region, copied, region.length)
    return builder.toString()
}

private fun lineOffsets(text: String): List<Int> {
    val offsets = mutableListOf(0)
    text.forEachIndexed { index, c -> if (c == '\n') offsets += index + 1 }
    return offsets
}
