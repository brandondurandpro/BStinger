package org.example.kotlineditor.unit

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.state.firstChangedLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** The line the diff editor's Open File goes to: the first change, in the new version of the file. */
class FirstChangedLineTest {
    private fun hunk() = DiffLine(DiffKind.Hunk, -1, -1, "@@")

    private fun context(old: Int, new: Int) = DiffLine(DiffKind.Context, old, new, "context")

    @Test
    fun `an added line is the line itself`() {
        val lines = listOf(hunk(), context(4, 4), DiffLine(DiffKind.Added, -1, 5, "new"), context(5, 6))
        assertEquals(4, firstChangedLine(lines))
    }

    @Test
    fun `removed lines go to the line that now stands where they were`() {
        val lines = listOf(hunk(), context(9, 9), DiffLine(DiffKind.Removed, 10, -1, "old"), context(11, 10))
        assertEquals(9, firstChangedLine(lines))
    }

    @Test
    fun `lines removed at the end of the file go just after the last line kept`() {
        val lines = listOf(hunk(), context(1, 1), DiffLine(DiffKind.Removed, 2, -1, "old"))
        assertEquals(1, firstChangedLine(lines))
    }

    @Test
    fun `no change, or a deleted file, goes to the first line`() {
        assertEquals(0, firstChangedLine(emptyList()))
        assertEquals(0, firstChangedLine(listOf(hunk(), DiffLine(DiffKind.Removed, 1, -1, "gone"))))
    }
}
