package org.example.kotlineditor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.example.kotlineditor.syntax.SyntaxColor
import org.example.kotlineditor.syntax.treesitter.LineSpans

// [text] (the displayed line) with its brackets colored by depth, from [startDepth] open where the line starts, like
// VS Code; a closing bracket nothing opened is red. Brackets inside a string or a comment of [spans] keep its color.
internal fun withBracketColors(
    text: AnnotatedString,
    spans: LineSpans,
    columns: IntArray?,
    startDepth: Int,
): AnnotatedString {
    if (startDepth < 0 || text.none { it in BRACKETS }) return text
    val quoted = quotedColumns(text.length, spans, columns)
    val builder = AnnotatedString.Builder(text)
    var depth = startDepth
    text.forEachIndexed { column, c ->
        val counted = !quoted[column]
        if (counted && c in OPENING_BRACKETS) {
            builder.addStyle(bracketStyle(depth), column, column + 1)
            depth++
        }
        if (counted && c in CLOSING_BRACKETS) {
            depth--
            val style = if (depth < 0) UNMATCHED_BRACKET_STYLE else bracketStyle(depth)
            builder.addStyle(style, column, column + 1)
            depth = maxOf(depth, 0)
        }
    }
    return builder.toAnnotatedString()
}

// The columns of [length] displayed ones that [spans] color as a string or a comment.
private fun quotedColumns(length: Int, spans: LineSpans, columns: IntArray?): BooleanArray {
    val quoted = BooleanArray(length)
    for (i in 0 until spans.size) {
        if (spans.styles[i] in QUOTED_STYLES) {
            val start = columns?.get(spans.starts[i]) ?: spans.starts[i]
            val end = columns?.get(spans.ends[i]) ?: spans.ends[i]
            quoted.fill(true, start.coerceIn(0, length), end.coerceIn(0, length))
        }
    }
    return quoted
}

private fun bracketStyle(depth: Int): SpanStyle = BRACKET_STYLES[depth % BRACKET_STYLES.size]

private const val OPENING_BRACKETS = "([{"
private const val CLOSING_BRACKETS = ")]}"
private const val BRACKETS = OPENING_BRACKETS + CLOSING_BRACKETS
private val BRACKET_STYLES = IdeColors.BracketDepths.map { SpanStyle(color = it) }
private val UNMATCHED_BRACKET_STYLE = SpanStyle(color = IdeColors.BracketUnmatched)
private val QUOTED_STYLES =
    setOf(SyntaxColor.Comment.ordinal, SyntaxColor.StringLiteral.ordinal, SyntaxColor.StringEscape.ordinal)
