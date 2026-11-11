package org.example.kotlineditor.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the transcript keeps of the output and of the changes of a tool call. */
class ShownContentTest {
    @Test
    fun `a short output is kept whole, its blocks one per line`() {
        assertEquals("first\nsecond", shownOutput(listOf("first", "", "second")))
        assertEquals("", shownOutput(emptyList()))
    }

    @Test
    fun `a long output keeps its beginning`() {
        val text = "0123456789".repeat(MAX_TOOL_OUTPUT)
        assertEquals(text.take(MAX_TOOL_OUTPUT), shownOutput(listOf(text)))
        val blocks = shownOutput(List(MAX_TOOL_OUTPUT) { "block $it" })
        assertTrue(blocks.length <= MAX_TOOL_OUTPUT, "${blocks.length} characters kept")
        assertTrue(blocks.startsWith("block 0\nblock 1\n"))
    }

    @Test
    fun `an output is never cut in the middle of a character`() {
        val smiley = "😀"
        val kept = shownOutput(listOf("a" + smiley.repeat(MAX_TOOL_OUTPUT)))
        assertFalse(Character.isHighSurrogate(kept.last()))
        assertEquals(MAX_TOOL_OUTPUT - 1, kept.length)
    }

    @Test
    fun `a change keeps the lines that differ, not the file around them`() {
        val before = (1..1_000).joinToString("\n") { "line $it" }
        val after = before.replace("line 500\n", "LINE 500\nand one more\n")
        val diff = shownDiff("/project/File.kt", before, after)
        assertEquals("/project/File.kt", diff.path)
        assertEquals(listOf("line 500"), diff.removed)
        assertEquals(listOf("LINE 500", "and one more"), diff.added)
        assertEquals(0, diff.hiddenLines)
    }

    @Test
    fun `lines added or removed alone show alone`() {
        val added = shownDiff("f", "a\nc", "a\nb\nc")
        assertEquals(emptyList(), added.removed)
        assertEquals(listOf("b"), added.added)
        val removed = shownDiff("f", "a\nb\nc", "a\nc")
        assertEquals(listOf("b"), removed.removed)
        assertEquals(emptyList(), removed.added)
        val same = shownDiff("f", "a\nb", "a\nb")
        assertEquals(emptyList(), same.removed + same.added)
    }

    @Test
    fun `a new file keeps its first lines and counts the others`() {
        val text = (1..500).joinToString("\n") { "line $it" }
        val diff = shownDiff("f", null, text)
        assertEquals(emptyList(), diff.removed)
        assertEquals((1..MAX_DIFF_LINES).map { "line $it" }, diff.added)
        assertEquals(500 - MAX_DIFF_LINES, diff.hiddenLines)
    }

    @Test
    fun `a file rewritten counts the lines left out on both sides`() {
        val before = (1..100).joinToString("\n") { "old $it" }
        val after = (1..60).joinToString("\n") { "new $it" }
        val diff = shownDiff("f", before, after)
        assertEquals(MAX_DIFF_LINES, diff.removed.size)
        assertEquals(MAX_DIFF_LINES, diff.added.size)
        assertEquals(100 - MAX_DIFF_LINES + 60 - MAX_DIFF_LINES, diff.hiddenLines)
    }

    @Test
    fun `a line of megabytes keeps its beginning`() {
        val diff = shownDiff("f", null, "x".repeat(MAX_DIFF_LINE * 100))
        assertEquals(listOf("x".repeat(MAX_DIFF_LINE)), diff.added)
    }
}
