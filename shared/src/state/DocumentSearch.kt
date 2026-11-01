package org.example.kotlineditor.state

import kotlin.math.abs

// Search, replace and bracket matching.

private const val DEFAULT_FIND_LIMIT = 10_000
private const val DEFAULT_BRACKET_SEARCH_LINES = 3000
private val BRACKETS = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSING_BRACKETS = BRACKETS.entries.associate { (open, close) -> close to open }

/** All occurrences of [query] (single-line), at most [limit]. */
fun Document.findAll(query: String, ignoreCase: Boolean = true, limit: Int = DEFAULT_FIND_LIMIT): List<TextPos> {
    val result = ArrayList<TextPos>()
    if (query.isNotEmpty() && '\n' !in query) {
        var index = 0
        while (index < lines.size && result.size < limit) {
            val line = lines[index]
            var from = line.indexOf(query, 0, ignoreCase)
            while (from >= 0 && result.size < limit) {
                result += TextPos(index, from)
                from = line.indexOf(query, from + query.length, ignoreCase)
            }
            index++
        }
    }
    return result
}

/** Replaces every occurrence at [positions] (each [length] chars long) in one undoable edit. */
fun Document.replaceAll(positions: List<TextPos>, length: Int, replacement: String) {
    if (positions.isEmpty()) return
    val byLine = positions.groupBy({ it.line }, { it.column })
    val first = byLine.keys.min()
    val last = byLine.keys.max()
    val newLines =
        (first..last).map { index ->
            byLine[index]?.let { replacedLine(lines[index], it, length, replacement) } ?: lines[index]
        }
    replace(TextPos(first, 0), TextPos(last, lines[last].length), newLines.joinToString("\n"))
}

private fun replacedLine(line: String, columns: List<Int>, length: Int, replacement: String): String =
    buildString {
        var from = 0
        for (column in columns.sorted().filter { it >= 0 }) {
            if (column >= from) {
                append(line, from, column).append(replacement)
                from = column + length
            }
        }
        append(line, minOf(from, line.length), line.length)
    }

/** The bracket next to the caret and its match, if any (searches at most [maxLines] lines away). */
fun Document.matchingBracket(maxLines: Int = DEFAULT_BRACKET_SEARCH_LINES): Pair<TextPos, TextPos>? {
    val line = lines[caret.line]
    return listOf(caret.column, caret.column - 1)
        .filter { it in line.indices && (line[it] in BRACKETS || line[it] in CLOSING_BRACKETS) }
        .firstNotNullOfOrNull { column ->
            val bracket = TextPos(caret.line, column)
            matchOf(bracket, maxLines)?.let { bracket to it }
        }
}

private fun Document.matchOf(from: TextPos, maxLines: Int): TextPos? {
    val bracket = lines[from.line][from.column]
    val partner = BRACKETS[bracket] ?: CLOSING_BRACKETS.getValue(bracket)
    val step = if (bracket in BRACKETS) 1 else -1
    var depth = 0
    var position: TextPos? = from
    var found: TextPos? = null
    while (found == null) {
        val current = position?.takeIf { abs(it.line - from.line) <= maxLines } ?: break
        depth +=
            when (lines[current.line][current.column]) {
                bracket -> 1
                partner -> -1
                else -> 0
            }
        if (depth == 0) found = current
        position = stepped(current, step)
    }
    return found
}

// Next character position in the given direction, skipping empty lines; null past the document ends.
private fun Document.stepped(position: TextPos, step: Int): TextPos? {
    var line = position.line
    var column = position.column + step
    while (line in lines.indices && column !in lines[line].indices) {
        line += step
        column = if (step > 0) 0 else lines.getOrNull(line)?.lastIndex ?: -1
    }
    return if (line in lines.indices) TextPos(line, column) else null
}
