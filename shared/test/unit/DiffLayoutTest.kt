package org.example.kotlineditor.unit

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.ui.sideBySideRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiffLayoutTest {
    @Test
    fun `removed and added lines face each other and a missing one leaves a blank cell`() {
        val rows =
            sideBySideRows(
                listOf(
                    DiffLine(DiffKind.Hunk, -1, -1, "@@ -1,3 +1,3 @@"),
                    DiffLine(DiffKind.Context, 1, 1, "context"),
                    DiffLine(DiffKind.Removed, 2, -1, "old one"),
                    DiffLine(DiffKind.Removed, 3, -1, "old two"),
                    DiffLine(DiffKind.Added, -1, 2, "new one"),
                    DiffLine(DiffKind.Context, 4, 3, "after"),
                ),
            )

        assertEquals(5, rows.size)
        assertEquals("@@ -1,3 +1,3 @@", rows[0].hunkText)
        assertEquals(listOf("context", "old one", "old two", "after"), rows.drop(1).map { it.left?.text })
        assertEquals(listOf("context", "new one", null, "after"), rows.drop(1).map { it.right?.text })
        // Context lines keep their own number on each side.
        assertEquals(listOf(1, 2, 3, 4), rows.drop(1).map { it.left?.number })
        assertEquals(listOf(1, 2, null, 3), rows.drop(1).map { it.right?.number })
    }

    @Test
    fun `more added lines than removed ones still align both sides`() {
        val rows =
            sideBySideRows(
                listOf(
                    DiffLine(DiffKind.Removed, 1, -1, "gone"),
                    DiffLine(DiffKind.Added, -1, 1, "first"),
                    DiffLine(DiffKind.Added, -1, 2, "second"),
                ),
            )

        assertEquals(2, rows.size)
        assertEquals(listOf("gone", null), rows.map { it.left?.text })
        assertEquals(listOf("first", "second"), rows.map { it.right?.text })
        assertNull(rows[1].left)
    }
}
