package org.example.kotlineditor.unit

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.state.CompareLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The line diff behind the conflict banner's Compare: what each side holds, bounded on very different files. */
class CompareLinesTest {
    private fun diff(disk: List<String>, editor: List<String>): List<DiffLine> = CompareLines.diff(disk, editor)

    @Test
    fun `identical contents are all context`() {
        val lines = listOf("# Demo", "", "A sample project.", "")
        val same = diff(disk = lines, editor = lines)
        assertEquals(lines.size, same.size)
        same.forEachIndexed { index, line ->
            assertEquals(DiffKind.Context, line.kind, "line $index")
            assertEquals(index + 1, line.oldLine, "line $index")
            assertEquals(index + 1, line.newLine, "line $index")
            assertEquals(lines[index], line.text, "line $index")
        }
    }

    @Test
    fun `a line added in the editor shows as an added line`() {
        val lines = diff(disk = listOf("a"), editor = listOf("a", "b"))
        assertEquals(listOf("a" to DiffKind.Context, "b" to DiffKind.Added), lines.map { it.text to it.kind })
        assertEquals(2, lines[1].newLine)
        assertEquals(-1, lines[1].oldLine)
    }

    @Test
    fun `a line missing from the editor shows as a removed line`() {
        val lines = diff(disk = listOf("a", "b"), editor = listOf("a"))
        assertEquals(listOf("a" to DiffKind.Context, "b" to DiffKind.Removed), lines.map { it.text to it.kind })
        assertEquals(2, lines[1].oldLine)
        assertEquals(-1, lines[1].newLine)
    }

    @Test
    fun `a line rewritten in the middle keeps its neighbours`() {
        val lines = diff(disk = listOf("a", "old", "c"), editor = listOf("a", "new", "c"))
        assertEquals(
            listOf(
                "a" to DiffKind.Context,
                "old" to DiffKind.Removed,
                "new" to DiffKind.Added,
                "c" to DiffKind.Context,
            ),
            lines.map { it.text to it.kind },
        )
        // A removal of the old file is numbered on it alone, an addition on the new one alone.
        assertEquals(2, lines[1].oldLine)
        assertEquals(-1, lines[1].newLine)
        assertEquals(-1, lines[2].oldLine)
        assertEquals(2, lines[2].newLine)
    }

    @Test
    fun `lines reordered are one removal and one addition`() {
        val lines = diff(disk = listOf("a", "b"), editor = listOf("b", "a"))
        val kinds = lines.associateBy { it.kind }.keys
        assertEquals(setOf(DiffKind.Context, DiffKind.Removed, DiffKind.Added), kinds)
        assertTrue(lines.none { it.newLine > 0 && it.oldLine > 0 && it.text.isEmpty() }, "no free context")
        // Both contents are covered: two lines each, one numbered on both sides.
        assertEquals(3, lines.size)
    }

    @Test
    fun `empty contents compare on both directions`() {
        assertTrue(diff(emptyList(), emptyList()).isEmpty())
        val created = diff(emptyList(), listOf("a"))
        assertEquals("a" to DiffKind.Added, created[0].text to created[0].kind)
        assertEquals(-1, created[0].oldLine)
        val deleted = diff(listOf("a"), emptyList())
        assertEquals("a" to DiffKind.Removed, deleted[0].text to deleted[0].kind)
        assertEquals(-1, deleted[0].newLine)
    }

    @Test
    fun `a middle too big for the table is shown as one whole difference`() {
        // 2,000 lines apart against each other: a table of more cells than the cap, found without hanging.
        val disk = List(SIDE_COUNT) { "disk line $it" }
        val editor = List(SIDE_COUNT) { "editor line $it" }
        val start = System.nanoTime()
        val lines = diff(disk = disk, editor = editor)
        // No line by line alignment: every disk line is removed, every editor line added, nothing shared.
        assertEquals(SIDE_COUNT, lines.count { it.kind == DiffKind.Removed })
        assertEquals(SIDE_COUNT, lines.count { it.kind == DiffKind.Added })
        assertTrue(lines.none { it.kind == DiffKind.Context }, "the whole middle differs")
        assertEquals(2 * SIDE_COUNT, lines.size)
        // The block keeps every content, ordered: the disk's lines first, then the editor's.
        assertEquals(1, lines[0].oldLine)
        assertEquals(-1, lines[0].newLine)
        assertEquals(SIDE_COUNT, lines[SIDE_COUNT - 1].oldLine)
        assertEquals(1, lines[SIDE_COUNT].newLine)
        assertEquals("editor line 0", lines[SIDE_COUNT].text)
        // And it is not the exact path either, which would take minutes on a quadratic table: near at once.
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 5_000, "a middle over the cap spent ${millis}ms")
    }

    @Test
    fun `a middle under the cap aligns exactly`() {
        // Just under the table cap: the exact alignment still runs, and does not overstep its relative order.
        val disk = List(TABLE_SIDE_COUNT) { "disk line $it" }
        val editor = List(TABLE_SIDE_COUNT) { "editor line $it" }
        val start = System.nanoTime()
        val lines = diff(disk = disk, editor = editor)
        assertEquals(TABLE_SIDE_COUNT, lines.count { it.kind == DiffKind.Removed })
        assertEquals(TABLE_SIDE_COUNT, lines.count { it.kind == DiffKind.Added })
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 10_000, "a middle under the cap spent ${millis}ms")
    }

    private companion object {
        // Middle sizes that fall on either side of the cap (a middle of n against n lines holds (n+1)^2 cells).
        const val TABLE_SIDE_COUNT = 1_400
        const val SIDE_COUNT = 2_000
    }
}
