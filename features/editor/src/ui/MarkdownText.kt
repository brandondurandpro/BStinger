package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter

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
internal fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = ArrayList<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(joinLines(paragraph))
        paragraph.clear()
    }
    val lines = markdown.replace("\r\n", "\n").split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        val fence = FENCE.matchEntire(trimmed)
        val heading = HEADING.matchEntire(trimmed)
        val item = ITEM.matchEntire(line)
        when {
            fence != null -> {
                flush()
                val code = ArrayList<String>()
                index++
                while (index < lines.size && !lines[index].trim().startsWith(fence.groupValues[1])) code += lines[index++]
                blocks += MarkdownBlock.Code(fence.groupValues[2].trim(), code.joinToString("\n").trimEnd())
            }

            trimmed.isEmpty() -> flush()
            RULE.matches(trimmed) -> {
                flush()
                blocks += MarkdownBlock.Rule
            }

            heading != null -> {
                flush()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }

            item != null -> {
                flush()
                blocks += MarkdownBlock.Item(item.groupValues[2], item.groupValues[3].trim())
            }

            else -> paragraph += line
        }
        index++
    }
    flush()
    return blocks
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
internal fun inlineMarkdown(text: String, code: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        var bold = false
        var italic = false
        while (index < text.length) {
            val c = text[index]
            val next = text.getOrNull(index + 1)
            when {
                c == '\\' && next != null && !next.isLetterOrDigit() -> {
                    append(next)
                    index += 2
                }

                c == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end < 0) {
                        append(c)
                        index++
                    } else {
                        withStyle(code) { append(text.substring(index + 1, end)) }
                        index = end + 1
                    }
                }

                (c == '*' || c == '_') && next == c -> {
                    if (bold) pop() else pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    bold = !bold
                    index += 2
                }

                (c == '*' || c == '_') && isEmphasis(text, index, italic) -> {
                    if (italic) pop() else pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    italic = !italic
                    index++
                }

                c == '[' -> {
                    val link = LINK.matchAt(text, index)
                    if (link == null) {
                        append(c)
                        index++
                    } else {
                        withStyle(SpanStyle(color = IdeColors.Link, textDecoration = TextDecoration.Underline)) { append(link.groupValues[1]) }
                        index += link.value.length
                    }
                }

                else -> {
                    append(c)
                    index++
                }
            }
        }
        // Unclosed emphasis: the styles end with the text.
        while (bold || italic) {
            pop()
            if (bold) bold = false else italic = false
        }
    }

// `*` or `_` opening emphasis is followed by a non-space, closing one preceded by one; `snake_case` is no emphasis.
private fun isEmphasis(text: String, index: Int, open: Boolean): Boolean {
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + 1)
    if (text[index] == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true) return false
    return if (open) before != null && !before.isWhitespace() else after != null && !after.isWhitespace() && text.indexOf(text[index], index + 1) > 0
}

private val FENCE = Regex("""^(```+|~~~+)\s*([\w+-]*).*$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
private val LINK = Regex("""\[([^\]]+)]\(([^)\s]*)[^)]*\)""")

/**
 * [markdown] laid out like IntelliJ's documentation popup: headings, paragraphs, lists, rules and code blocks, the
 * PHP code colored like the editor.
 */
@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val codeStyle = LocalCodeStyle.current
    val text = TextStyle(color = IdeColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
    val inlineCode = SpanStyle(fontFamily = codeStyle.fontFamily, background = IdeColors.DocumentationCode)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val size = if (block.level <= 2) 15.sp else 13.sp
                    BasicText(inlineMarkdown(block.text, inlineCode), style = text.copy(fontSize = size, fontWeight = FontWeight.Bold))
                }

                is MarkdownBlock.Paragraph -> BasicText(inlineMarkdown(block.text, inlineCode), style = text)
                is MarkdownBlock.Item -> {
                    Row {
                        val bullet = if (block.marker.first().isDigit()) block.marker else "•"
                        BasicText(bullet, Modifier.width(16.dp), style = text.copy(color = IdeColors.TextSecondary))
                        BasicText(inlineMarkdown(block.text, inlineCode), style = text)
                    }
                }

                is MarkdownBlock.Code -> CodeBlock(block)
                MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val codeStyle = LocalCodeStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = IdeColors.Code)
    // `<?php ` only tells the highlighter the language: it is not shown.
    val code = block.text.removePrefix("<?php ").removePrefix("<?php\n")
    // PHP and the languages hovers show are C-like for the line lexer.
    val language = if (block.language == "sh" || block.language == "bash") Language.Script else Language.CLike
    val highlighted = remember(code) { highlightedCode(code, language) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.DocumentationCode, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicText(highlighted, style = codeStyle)
    }
}

private fun highlightedCode(code: String, language: Language): AnnotatedString =
    buildAnnotatedString {
        var inComment = false
        code.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val spans = SyntaxHighlighter.highlight(line, language, inComment)
            val start = length
            append(line)
            for (i in 0 until spans.size) addStyle(syntaxStyle(spans.styles[i]), start + spans.starts[i], start + spans.ends[i])
            inComment = line.contains("/*") && !line.contains("*/")
        }
    }
