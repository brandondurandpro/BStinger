package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable

/** A position in a document: zero-based line and column (in characters). */
@Immutable
data class TextPos(val line: Int, val column: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

const val TAB_SIZE = 4

/**
 * Column on screen of [column] in [line], tabs counting as [TAB_SIZE] cells, and the cells of the [inlays] drawn
 * before it (those at [column] included: the caret goes after a hint, before the character it precedes).
 */
fun visualColumn(line: String, column: Int, inlays: LineInlays? = null): Int {
    val cells = (0 until minOf(column, line.length)).sumOf { if (line[it] == '\t') TAB_SIZE else 1 }
    return cells + (inlays?.cellsBefore(column) ?: 0)
}

/** Inverse of [visualColumn]: the character column closest to the [visual] cell; a cell of a hint is its column. */
fun columnForVisual(line: String, visual: Int, inlays: LineInlays? = null): Int {
    var cells = 0
    var column = 0
    var found = false
    while (column < line.length && !found) {
        cells += inlays?.cellsAt(column) ?: 0
        val width = if (line[column] == '\t') TAB_SIZE else 1
        found = visual < cells + (width + 1) / 2
        if (!found) {
            cells += width
            column++
        }
    }
    return column
}

fun displayLine(line: String): String = if ('\t' in line) line.replace("\t", " ".repeat(TAB_SIZE)) else line

internal fun Char.isWordChar() = isLetterOrDigit() || this == '_'

/** The word (letters, digits, `_`) around [column] in [line]; empty when there is none. */
internal fun wordAround(line: String, column: Int): IntRange {
    var start = column.coerceIn(0, line.length)
    var end = start
    while (start > 0 && line[start - 1].isWordChar()) start--
    while (end < line.length && line[end].isWordChar()) end++
    return start until end
}

/** Columns where [word] occurs in [line] as a whole word. */
fun wordOccurrences(line: String, word: String): IntArray? {
    val result = ArrayList<Int>()
    var from = line.indexOf(word)
    while (from >= 0) {
        if (wordAround(line, from) == from until from + word.length) result += from
        from = line.indexOf(word, from + word.length)
    }
    return result.takeIf { it.isNotEmpty() }?.toIntArray()
}

internal fun previousWordBoundary(line: String, column: Int): Int {
    var i = column
    while (i > 0 && !line[i - 1].isWordChar()) i--
    while (i > 0 && line[i - 1].isWordChar()) i--
    return i
}

internal fun nextWordBoundary(line: String, column: Int): Int {
    var i = column
    while (i < line.length && !line[i].isWordChar()) i++
    while (i < line.length && line[i].isWordChar()) i++
    return i
}

/** [pos] moved inside the document. */
fun Document.clamp(pos: TextPos): TextPos {
    val line = pos.line.coerceIn(0, lines.lastIndex)
    return TextPos(line, pos.column.coerceIn(0, lines[line].length))
}

/** Text between [start] and [end], lines joined with `\n`. */
fun Document.textBetween(start: TextPos, end: TextPos): String =
    if (start.line == end.line) {
        lines[start.line].substring(start.column, end.column)
    } else {
        buildString {
            append(lines[start.line], start.column, lines[start.line].length)
            for (i in start.line + 1 until end.line) append('\n').append(lines[i])
            append('\n').append(lines[end.line], 0, end.column)
        }
    }

fun Document.selectedText(): String = selectionRange()?.let { (s, e) -> textBetween(s, e) } ?: ""
