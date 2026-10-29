package org.example.kotlineditor.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import org.example.kotlineditor.ui.IdeColors

/**
 * Line-oriented highlighter. Lines are highlighted lazily when they become visible; the only cross-line state
 * (being inside a block comment) is precomputed once per document in [blockCommentStates].
 */
object SyntaxHighlighter {
    private val styles =
        mapOf(
            TokenKind.Keyword to SpanStyle(color = IdeColors.Keyword),
            TokenKind.StringLiteral to SpanStyle(color = IdeColors.StringLiteral),
            TokenKind.Number to SpanStyle(color = IdeColors.Number),
            TokenKind.Comment to SpanStyle(color = IdeColors.Comment, fontStyle = FontStyle.Italic),
            TokenKind.Annotation to SpanStyle(color = IdeColors.Annotation),
            TokenKind.Declaration to SpanStyle(color = IdeColors.FunctionDeclaration),
        )

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

    fun highlight(line: String, language: Language, startsInComment: Boolean): AnnotatedString =
        if (language == Language.Plain || line.isEmpty()) {
            AnnotatedString(line)
        } else {
            val builder = AnnotatedString.Builder(line)
            LineLexer(
                line,
                language,
            ).run(startsInComment).tokens.forEach { builder.addStyle(styles.getValue(it.kind), it.start, it.end) }
            builder.toAnnotatedString()
        }
}
