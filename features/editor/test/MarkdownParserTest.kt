package org.example.kotlineditor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The blocks of Markdown the documentation popups and the answers of the agent show. */
class MarkdownParserTest {
    @Test
    fun `a table keeps its rows, as written`() {
        val blocks = parseMarkdown(lines("Sizes:", "", "| Name | Value |", "|---|---|", "| size | 3 |", "", "Done."))
        val expected =
            listOf(
                MarkdownBlock.Paragraph("Sizes:"),
                MarkdownBlock.Code(TABLE, lines("| Name | Value |", "|---|---|", "| size | 3 |")),
                MarkdownBlock.Paragraph("Done."),
            )
        assertEquals(expected, blocks)
    }

    @Test
    fun `a code block still streaming takes the rest of the text`() {
        val blocks = parseMarkdown(lines("Here:", "```kotlin", "fun a() = 1", "fun b"))
        assertEquals(MarkdownBlock.Code("kotlin", lines("fun a() = 1", "fun b")), blocks.lastOrNull())
    }

    @Test
    fun `headings, items and paragraphs`() {
        val blocks = parseMarkdown(lines("# Title", "", "- one", "- two", "", "Text", "on two lines"))
        val expected =
            listOf(
                MarkdownBlock.Heading(1, "Title"),
                MarkdownBlock.Item("-", "one"),
                MarkdownBlock.Item("-", "two"),
                MarkdownBlock.Paragraph("Text on two lines"),
            )
        assertEquals(expected, blocks)
    }

    private fun lines(vararg lines: String): String = lines.joinToString("\n")
}
