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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter

private val BLOCK_SPACING = 6.dp
private val BULLET_WIDTH = 16.dp
private val HEADING_SIZE = 15.sp
private val TEXT_SIZE = 13.sp

/**
 * [markdown] laid out like IntelliJ's documentation popup: headings, paragraphs, lists, rules, tables and code blocks,
 * the code colored like the editor. The documentation of the language servers and the answers of the agent use it.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val codeStyle = LocalCodeStyle.current
    val text = TextStyle(color = IdeColors.Text, fontSize = TEXT_SIZE, lineHeight = 19.sp)
    val inlineCode = SpanStyle(fontFamily = codeStyle.fontFamily, background = IdeColors.DocumentationCode)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING)) {
        for (block in blocks) Block(block, text, inlineCode)
    }
}

@Composable
private fun Block(block: MarkdownBlock, text: TextStyle, inlineCode: SpanStyle) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val size = if (block.level <= 2) HEADING_SIZE else TEXT_SIZE
            BasicText(
                inlineMarkdown(block.text, inlineCode),
                style = text.copy(fontSize = size, fontWeight = FontWeight.Bold),
            )
        }

        is MarkdownBlock.Paragraph -> {
            BasicText(inlineMarkdown(block.text, inlineCode), style = text)
        }

        is MarkdownBlock.Item -> {
            Item(block, text, inlineCode)
        }

        is MarkdownBlock.Code -> {
            CodeBlock(block)
        }

        MarkdownBlock.Rule -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        }
    }
}

@Composable
private fun Item(block: MarkdownBlock.Item, text: TextStyle, inlineCode: SpanStyle) {
    Row {
        val numbered = block.marker.firstOrNull()?.isDigit() == true
        val bullet = if (numbered) block.marker else "•"
        BasicText(bullet, Modifier.width(BULLET_WIDTH), style = text.copy(color = IdeColors.TextSecondary))
        BasicText(inlineMarkdown(block.text, inlineCode), style = text)
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val codeStyle = LocalCodeStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = IdeColors.Code)
    // `<?php ` only tells the highlighter the language: it is not shown.
    val code = block.text.removePrefix("<?php ").removePrefix("<?php\n")
    val language = fenceLanguage(block.language)
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

// The language of a fence (```kotlin): PHP and most languages are C-like for the line lexer; a table or a fence
// without a language is plain text.
private fun fenceLanguage(name: String): Language =
    when (name.lowercase()) {
        "kotlin", "kt", "kts" -> Language.Kotlin
        "java" -> Language.Java
        "sh", "bash", "shell", "zsh", "console", "python", "py", "yaml", "yml", "toml", "ruby" -> Language.Script
        "", "text", "txt", "plain", TABLE -> Language.Plain
        else -> Language.CLike
    }

private fun highlightedCode(code: String, language: Language): AnnotatedString =
    buildAnnotatedString {
        var inComment = false
        code.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val spans = SyntaxHighlighter.highlight(line, language, inComment)
            val start = length
            append(line)
            for (i in 0 until spans.size) {
                addStyle(
                    syntaxStyle(spans.styles[i]),
                    start + spans.starts[i],
                    start + spans.ends[i],
                )
            }
            inComment = line.contains("/*") && !line.contains("*/")
        }
    }
