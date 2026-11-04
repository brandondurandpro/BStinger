package org.example.kotlineditor.syntax

import org.example.kotlineditor.syntax.treesitter.LineSpans

/** What each line of a document starts inside of: the state a line needs from the lines above it. */
class LineStarts(
    /** `inComment[i]` is true when line `i` starts inside a block comment. `null` if the language has none. */
    val inComment: BooleanArray?,
    /** `bracketDepths[i]` brackets are open where line `i` starts. `null` when brackets are not colored. */
    val bracketDepths: IntArray?,
)

/**
 * Line-oriented highlighter. Lines are highlighted lazily when they become visible; the cross-line state (being
 * inside a block comment, the brackets open) is precomputed once per document in [lineStarts].
 */
object SyntaxHighlighter {
    /** `result[i]` is true when line `i` starts inside a block comment. `null` if the language has none. */
    fun blockCommentStates(lines: List<String>, language: Language): BooleanArray? =
        lineStarts(lines, language).inComment

    /** Comment and bracket state where each line of [lines] starts, in one pass; nothing for plain text. */
    fun lineStarts(lines: List<String>, language: Language): LineStarts {
        if (language == Language.Plain) return LineStarts(null, null)
        val states = if (language.slashComments) BooleanArray(lines.size) else null
        val depths = IntArray(lines.size)
        var inComment = false
        var depth = 0
        lines.forEachIndexed { index, line ->
            states?.set(index, inComment)
            depths[index] = depth
            val lexer = LineLexer(line, language, collect = false, startDepth = depth).run(inComment)
            inComment = lexer.endsInComment
            depth = lexer.depth
        }
        return LineStarts(states, depths)
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
