package org.example.kotlineditor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val FENCE = Regex("""^(```+|~~~+)\s*([\w+-]*).*$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
private val LINK = Regex("""\[([^\]]+)]\(([^)\s]*)[^)]*\)""")
private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val LINK_STYLE = SpanStyle(color = IdeColors.Link, textDecoration = TextDecoration.Underline)

/** One block of a Markdown document. */
@Immutable
internal sealed interface MarkdownBlock {
    /** `#`, `##`... */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** Lines of text, joined. */
    data class Paragraph(val text: String) : MarkdownBlock

    /** An item of a list (`-`, `*`, `1.`). */
    data class Item(val marker: String, val text: String) : MarkdownBlock

    /** A fenced code block. */
    data class Code(val language: String, val text: String) : MarkdownBlock

    /** `---`. */
    data object Rule : MarkdownBlock
}

/** The blocks of [markdown]: what language servers write in their hovers (CommonMark, without HTML). */
internal fun parseMarkdown(markdown: String): List<MarkdownBlock> =
    MarkdownParser(markdown.replace("\r\n", "\n").split('\n')).blocks()

// Reads the lines one block at a time; the lines of a paragraph wait for the block that ends it.
private class MarkdownParser(private val lines: List<String>) {
    private val blocks = ArrayList<MarkdownBlock>()
    private val paragraph = ArrayList<String>()
    private var index = 0

    fun blocks(): List<MarkdownBlock> {
        while (index < lines.size) {
            val line = lines[index]
            index++
            read(line)
        }
        flush()
        return blocks
    }

    private fun read(line: String) {
        val trimmed = line.trim()
        val fence = FENCE.matchEntire(trimmed)
        val heading = HEADING.matchEntire(trimmed)
        val item = ITEM.matchEntire(line)
        when {
            fence != null -> code(fence.groupValues[1], fence.groupValues[2].trim())
            trimmed.isEmpty() -> flush()
            RULE.matches(trimmed) -> add(MarkdownBlock.Rule)
            heading != null -> add(headingOf(heading))
            item != null -> add(itemOf(item))
            else -> paragraph += line
        }
    }

    private fun headingOf(match: MatchResult): MarkdownBlock {
        val (marks, text) = match.destructured
        return MarkdownBlock.Heading(marks.length, text.trim())
    }

    private fun itemOf(match: MatchResult): MarkdownBlock {
        val (_, marker, text) = match.destructured
        return MarkdownBlock.Item(marker, text.trim())
    }

    // The lines up to the closing fence (or the end of the text).
    private fun code(fence: String, language: String) {
        val code = ArrayList<String>()
        while (index < lines.size && !lines[index].trim().startsWith(fence)) {
            code += lines[index]
            index++
        }
        // The closing fence.
        index++
        add(MarkdownBlock.Code(language, code.joinToString("\n").trimEnd()))
    }

    private fun add(block: MarkdownBlock) {
        flush()
        blocks += block
    }

    private fun flush() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(joinLines(paragraph))
        paragraph.clear()
    }
}

// A line ending with two spaces or a backslash breaks; other lines join with a space.
private fun joinLines(lines: List<String>): String =
    buildString {
        lines.forEachIndexed { index, line ->
            val hardBreak = line.endsWith("  ") || line.endsWith("\\")
            append(line.trimEnd().removeSuffix("\\").trim())
            if (index < lines.lastIndex) append(if (hardBreak) "\n" else " ")
        }
    }

/** Inline Markdown: `**bold**`, `*italic*`, `_italic_`, `` `code` ``, `[link](url)`, `\` escapes. */
internal fun inlineMarkdown(text: String, code: SpanStyle): AnnotatedString = InlineMarkdown(text, code).read()

// One pass over the text; each token says how many characters it took.
private class InlineMarkdown(private val text: String, private val code: SpanStyle) {
    private val builder = AnnotatedString.Builder()
    private var bold = false
    private var italic = false

    fun read(): AnnotatedString {
        var index = 0
        while (index < text.length) index += token(index)
        // Unclosed emphasis: the styles end with the text.
        if (bold) builder.pop()
        if (italic) builder.pop()
        return builder.toAnnotatedString()
    }

    private fun token(index: Int): Int {
        val c = text[index]
        val next = text.getOrNull(index + 1)
        val marker = c == '*' || c == '_'
        val escaped = c == '\\' && next != null && !next.isLetterOrDigit()
        return when {
            escaped -> plain("$next", 2)
            c == '`' -> code(index)
            marker && next == c -> bold()
            marker && isEmphasis(index) -> italic()
            c == '[' -> link(index)
            else -> plain("$c", 1)
        }
    }

    private fun plain(value: String, length: Int): Int {
        builder.append(value)
        return length
    }

    private fun code(index: Int): Int {
        val end = text.indexOf('`', index + 1)
        if (end < 0) return plain("`", 1)
        styled(code, text.substring(index + 1, end))
        return end + 1 - index
    }

    private fun link(index: Int): Int {
        val link = LINK.matchAt(text, index) ?: return plain("[", 1)
        styled(LINK_STYLE, link.groupValues[1])
        return link.value.length
    }

    private fun styled(style: SpanStyle, value: String) {
        builder.pushStyle(style)
        builder.append(value)
        builder.pop()
    }

    private fun bold(): Int {
        if (bold) builder.pop() else builder.pushStyle(BOLD)
        bold = !bold
        return 2
    }

    private fun italic(): Int {
        if (italic) builder.pop() else builder.pushStyle(ITALIC)
        italic = !italic
        return 1
    }

    // `*` or `_` opening emphasis is followed by a non-space, closing one preceded by one; `snake_case` is none.
    private fun isEmphasis(index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        val inWord = before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
        val closes = text.indexOf(text[index], index + 1) > 0
        val opens = after != null && !after.isWhitespace() && closes
        val ends = before != null && !before.isWhitespace()
        val emphasis = if (italic) ends else opens
        return emphasis && !(text[index] == '_' && inWord)
    }
}
