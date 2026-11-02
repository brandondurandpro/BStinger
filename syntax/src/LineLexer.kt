package org.example.kotlineditor.syntax

/** What a highlighted range of a line is. */
internal enum class TokenKind(val color: SyntaxColor) {
    Comment(SyntaxColor.Comment),
    Keyword(SyntaxColor.Keyword),
    StringLiteral(SyntaxColor.StringLiteral),
    Number(SyntaxColor.Number),
    Annotation(SyntaxColor.Annotation),
    Declaration(SyntaxColor.Declaration),
}

internal class Token(val kind: TokenKind, val start: Int, val end: Int)

private enum class Lexeme { LineComment, BlockComment, Quote, Digit, Annotation, Word, Other }

private val QUOTES = setOf('"', '\'')
private val DECLARING_WORDS = setOf("fun", "def", "function")
private val TRANSPARENT = setOf('<', '>', '.')

/**
 * Splits one line into highlighted tokens. [collect] false only follows block comments (to precompute, for a whole
 * document, which lines start inside one) without allocating tokens.
 */
internal class LineLexer(
    private val line: String,
    private val language: Language,
    private val collect: Boolean = true,
) {
    val tokens = ArrayList<Token>()

    var endsInComment = false
        private set

    private var i = 0
    private var previousWord: String? = null

    fun run(startsInComment: Boolean): LineLexer {
        if (startsInComment) blockComment(tokenStart = 0, contentStart = 0)
        while (i < line.length) next()
        return this
    }

    private fun next() {
        when (classify(line[i])) {
            Lexeme.LineComment -> {
                add(TokenKind.Comment, i, line.length)
                i = line.length
            }

            Lexeme.BlockComment -> {
                blockComment(tokenStart = i, contentStart = i + 2)
            }

            Lexeme.Quote -> {
                string()
            }

            Lexeme.Digit -> {
                span(TokenKind.Number) { it.isLetterOrDigit() || it == '.' || it == '_' }
            }

            Lexeme.Annotation -> {
                span(TokenKind.Annotation) { it.isJavaIdentifierPart() || it == '.' }
            }

            Lexeme.Word -> {
                word()
            }

            Lexeme.Other -> {
                other()
            }
        }
    }

    private fun classify(c: Char): Lexeme = commentAt(c) ?: codeAt(c)

    private fun commentAt(c: Char): Lexeme? {
        val slashLine = language.slashComments && line.startsWith("//", i)
        val hashLine = language.hashComments && c == '#'
        return when {
            slashLine || hashLine -> Lexeme.LineComment
            language.slashComments && line.startsWith("/*", i) -> Lexeme.BlockComment
            else -> null
        }
    }

    private fun codeAt(c: Char): Lexeme {
        val backtick = c == '`'
        val backtickString = backtick && language != Language.Kotlin
        return when {
            c in QUOTES || backtickString -> Lexeme.Quote
            c.isDigit() -> Lexeme.Digit
            c == '@' && line.getOrNull(i + 1)?.isJavaIdentifierStart() == true -> Lexeme.Annotation
            c.isJavaIdentifierStart() || backtick -> Lexeme.Word
            else -> Lexeme.Other
        }
    }

    private fun blockComment(tokenStart: Int, contentStart: Int) {
        val end = line.indexOf("*/", contentStart)
        endsInComment = end < 0
        i = if (end < 0) line.length else end + 2
        add(TokenKind.Comment, tokenStart, i)
    }

    private fun string() {
        val quote = line[i]
        var j = i + 1
        while (j < line.length && line[j] != quote) j += if (line[j] == '\\') 2 else 1
        val end = minOf(j + 1, line.length)
        add(TokenKind.StringLiteral, i, end)
        i = end
    }

    private inline fun span(kind: TokenKind, continues: (Char) -> Boolean) {
        var j = i + 1
        while (j < line.length && continues(line[j])) j++
        add(kind, i, j)
        i = j
    }

    private fun word() {
        var j = i + 1
        while (j < line.length && isWordPart(line[j])) j++
        if (collect) {
            val word = line.substring(i, j)
            when {
                word in language.keywords -> add(TokenKind.Keyword, i, j)
                previousWord in DECLARING_WORDS -> add(TokenKind.Declaration, i, j)
                else -> Unit
            }
            previousWord = word
        }
        i = j
    }

    private fun other() {
        val c = line[i]
        if (!c.isWhitespace() && c !in TRANSPARENT) previousWord = null
        i++
    }

    private fun add(kind: TokenKind, start: Int, end: Int) {
        if (collect && end > start) tokens += Token(kind, start, end)
    }
}

private fun isWordPart(c: Char): Boolean = c.isJavaIdentifierPart() || c == '`'
