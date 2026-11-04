package org.example.kotlineditor.unit

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.ListCursor
import org.example.kotlineditor.ui.highlightIndices
import org.example.kotlineditor.ui.highlightRange
import kotlin.test.Test
import kotlin.test.assertEquals

class PopupKitTest {
    // Ranges of the spans of [text] whose style satisfies [predicate], in order.
    private fun rangesOf(text: AnnotatedString, predicate: (SpanStyle) -> Boolean): List<IntRange> =
        text.spanStyles.filter { predicate(it.item) }.map { it.start until it.end }

    private fun accentRanges(text: AnnotatedString) = rangesOf(text) { it.color == IdeColors.Accent }

    private fun matchRanges(text: AnnotatedString) = rangesOf(text) { it.background == IdeColors.SearchMatch }

    @Test
    fun `the cursor moves and wraps around at both ends`() {
        val cursor = ListCursor()
        cursor.move(1, 3)
        assertEquals(1, cursor.selected)
        cursor.move(2, 3)
        assertEquals(0, cursor.selected)
        cursor.move(-1, 3)
        assertEquals(2, cursor.selected)
    }

    @Test
    fun `the cursor stays put on an empty list`() {
        val cursor = ListCursor()
        cursor.move(1, 0)
        cursor.move(-1, 0)
        assertEquals(0, cursor.selected)
    }

    @Test
    fun `fuzzy matched characters are emphasized one by one`() {
        val text = highlightIndices("ToolWindow", listOf(0, 4))
        assertEquals("ToolWindow", text.text)
        assertEquals(listOf(0..0, 4..4), accentRanges(text))
        assertEquals(listOf(0..9), rangesOf(text) { it.color == IdeColors.Text })
    }

    @Test
    fun `indices outside the text are ignored`() {
        val text = highlightIndices("abc", listOf(-1, 2, 3, 99))
        assertEquals(listOf(2..2), accentRanges(text))
    }

    @Test
    fun `a search result highlights its match`() {
        val text = highlightRange("val answer = 42", 4, 6)
        assertEquals("val answer = 42", text.text)
        assertEquals(listOf(4..9), matchRanges(text))
    }

    @Test
    fun `a match running past the end of the line is cut at the end`() {
        val text = highlightRange("abc", 1, 10)
        assertEquals(listOf(1..2), matchRanges(text))
    }

    @Test
    fun `an empty match or one outside the line highlights nothing`() {
        assertEquals(emptyList(), matchRanges(highlightRange("abc", 1, 0)))
        assertEquals(emptyList(), matchRanges(highlightRange("abc", 5, 2)))
        assertEquals(emptyList(), matchRanges(highlightRange("abc", -3, 2)))
    }

    @Test
    fun `a long line is cut to its preview, and a match beyond it highlights nothing`() {
        val line = "x".repeat(1000)
        assertEquals(300, highlightRange(line, 0, 1).text.length)
        assertEquals(listOf(295..299), matchRanges(highlightRange(line, 295, 50)))
        assertEquals(emptyList(), matchRanges(highlightRange(line, 500, 3)))
    }
}
