package org.example.kotlineditor.syntax

import org.example.kotlineditor.syntax.treesitter.LineSpans

/**
 * Line-oriented highlighter. Lines are highlighted lazily when they become visible; the only cross-line state
 * (being inside a block comment) is precomputed once per document in [blockCommentStates].
 */
object SyntaxHighlighter {
    /** `result[i]` is true when line `i` starts inside a block comment. `null` if the language has none. */
    fun blockCommentStates(lines: List<String>, language: Language): BooleanArray? =
        if (language.slashComments) {
            val states = BooleanArray(lines.size)
            var inComment = false
            lines.forEachIndexed { index, line ->
                states[index] = inComment
                inComment = LineLexer(line, language, collect = false).run(inComment).endsInComment
            }
            states
        } else {
            null
        }

    /** The colored ranges of [line], for languages without a tree-sitter grammar (or until it is loaded). */
    fun highlight(line: String, language: Language, startsInComment: Boolean): LineSpans {
        if (language == Language.Plain || line.isEmpty()) return LineSpans.EMPTY
        val tokens = LineLexer(line, language).run(startsInComment).tokens
        val starts = IntArray(tokens.size) { tokens[it].start }
        val ends = IntArray(tokens.size) { tokens[it].end }
        val colors = IntArray(tokens.size) { tokens[it].kind.color.ordinal }
        return LineSpans(starts, ends, colors)
    }
}
