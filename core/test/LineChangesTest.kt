package org.example.kotlineditor.unit

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitChangeKind.Added
import org.example.kotlineditor.model.GitChangeKind.Deleted
import org.example.kotlineditor.model.GitChangeKind.Modified
import org.example.kotlineditor.model.GitLineChange
import org.example.kotlineditor.state.lineChangesOf
import kotlin.test.Test
import kotlin.test.assertEquals

class LineChangesTest {
    private fun hunk(text: String = "@@ -1,2 +1,2 @@"): DiffLine = DiffLine(DiffKind.Hunk, -1, -1, text)

    private fun context(old: Int, new: Int, text: String = "context"): DiffLine =
        DiffLine(DiffKind.Context, old, new, text)

    private fun removed(old: Int, text: String = "old"): DiffLine = DiffLine(DiffKind.Removed, old, -1, text)

    private fun added(new: Int, text: String = "new"): DiffLine = DiffLine(DiffKind.Added, -1, new, text)

    @Test
    fun `no differences means no mark`() {
        assertEquals(emptyList(), lineChangesOf(emptyList()))
        assertEquals(emptyList(), lineChangesOf(listOf(hunk(), context(1, 1), context(2, 2))))
    }

    @Test
    fun `a replaced line is modified`() {
        val changes =
            lineChangesOf(
                listOf(hunk(), context(1, 1), removed(2, "two"), added(2, "TWO"), context(3, 3)),
            )

        assertEquals(listOf(GitLineChange(1, Modified)), changes)
    }

    @Test
    fun `extra added lines are added`() {
        val changes =
            lineChangesOf(
                listOf(hunk(), context(1, 1), removed(2, "two"), added(2, "TWO"), added(3, "three")),
            )

        assertEquals(
            listOf(GitLineChange(1, Modified), GitLineChange(2, Added)),
            changes,
        )
    }

    @Test
    fun `a new file is entirely added`() {
        val changes = lineChangesOf(listOf(hunk(), added(1, "one"), added(2, "two")))

        assertEquals(listOf(GitLineChange(0, Added), GitLineChange(1, Added)), changes)
    }

    @Test
    fun `a removed block is anchored before the next new line`() {
        val changes =
            lineChangesOf(
                listOf(hunk(), context(1, 1), removed(2, "two"), context(3, 2, "three")),
            )

        assertEquals(listOf(GitLineChange(1, Deleted)), changes)
    }

    @Test
    fun `a block removed at the end is anchored after the last line`() {
        val changes = lineChangesOf(listOf(hunk(), context(1, 1), removed(2, "two")))

        assertEquals(listOf(GitLineChange(1, Deleted)), changes)
    }

    @Test
    fun `a block with more removed than added lines keeps a deletion mark`() {
        val changes =
            lineChangesOf(
                listOf(hunk(), context(1, 1), removed(2, "two"), removed(3, "three"), added(2, "TWO")),
            )

        assertEquals(
            listOf(GitLineChange(1, Modified), GitLineChange(1, Deleted)),
            changes,
        )
    }

    @Test
    fun `marks of separate hunks keep their document order`() {
        val changes =
            lineChangesOf(
                listOf(
                    hunk(),
                    context(1, 1),
                    removed(2, "two"),
                    added(2, "TWO"),
                    hunk("@@ -8,2 +8,2 @@"),
                    context(8, 8),
                    removed(9, "nine"),
                    added(9, "NINE"),
                ),
            )

        assertEquals(
            listOf(GitLineChange(1, Modified), GitLineChange(8, Modified)),
            changes,
        )
    }
}
