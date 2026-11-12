package org.example.kotlineditor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.example.kotlineditor.syntax.SyntaxColor
import org.example.kotlineditor.syntax.treesitter.LineSpans

// [text] (the displayed line) with its brackets colored by depth, from [startDepth] open where the line starts, like
// VS Code; a closing bracket nothing opened is red. Brackets inside a string or a comment of [spans] keep its color.
// Only the first [limit] characters are looked at: the ones of a long line that are colored.
internal fun withBracketColors(
    text: AnnotatedString,
    spans: LineSpans,
    columns: IntArray?,
    startDepth: Int,
    limit: Int = text.length,
): AnnotatedString {
    val length = minOf(limit, text.length)
    val hasBrackets = (0 until length).any { text[it] in BRACKETS }
    if (startDepth < 0 || !hasBrackets) return text
    val quoted = quotedColumns(length, spans, columns)
    val builder = AnnotatedString.Builder(text)
    var depth = startDepth
    for (column in 0 until length) {
        val c = text[column]
        val counted = !quoted[column]
        if (counted && c in OPENING_BRACKETS) {
            builder.addStyle(bracketStyle(depth), column, column + 1)
            depth++
        }
        if (counted && c in CLOSING_BRACKETS) {
            depth--
            val style = if (depth < 0) currentBracketStyles().unmatched else bracketStyle(depth)
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

private fun bracketStyle(depth: Int): SpanStyle {
    val styles = currentBracketStyles().depths
    return styles[depth % styles.size]
}

private const val OPENING_BRACKETS = "([{"
private const val CLOSING_BRACKETS = ")]}"
private const val BRACKETS = OPENING_BRACKETS + CLOSING_BRACKETS

// The styles of the palette last used, built again when the theme changes.
private class BracketStyles(val palette: IdePalette) {
    val depths = palette.bracketDepths.map { SpanStyle(color = it) }
    val unmatched = SpanStyle(color = palette.bracketUnmatched)
}

private var bracketStyles: BracketStyles? = null

private fun currentBracketStyles(): BracketStyles {
    val palette = IdeColors.palette
    val known = bracketStyles
    if (known != null && known.palette === palette) return known
    val built = BracketStyles(palette)
    bracketStyles = built
    return built
}

private val QUOTED_STYLES =
    setOf(SyntaxColor.Comment.ordinal, SyntaxColor.StringLiteral.ordinal, SyntaxColor.StringEscape.ordinal)
