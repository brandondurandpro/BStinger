package org.example.kotlineditor.unit

import androidx.compose.ui.geometry.Offset
import org.example.kotlineditor.ui.CellMetrics
import org.example.kotlineditor.ui.CellPos
import org.example.kotlineditor.ui.TerminalSelection
import org.example.kotlineditor.ui.cellAt
import org.example.kotlineditor.ui.ordered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalSelectionTest {
    // A cell of a monospaced font: the width is a glyph's exact advance, not a whole number of pixels.
    private val metrics = CellMetrics(width = 8.5f, height = 16)

    @Test
    fun `ends on the same row are ordered by column`() {
        val left = CellPos(row = 3, column = 2)
        val right = CellPos(row = 3, column = 9)
        assertEquals(left to right, ordered(left, right))
        assertEquals(left to right, ordered(right, left))
    }

    @Test
    fun `ends on different rows are ordered by row, whatever their columns`() {
        val top = CellPos(row = 1, column = 40)
        val bottom = CellPos(row = 4, column = 0)
        assertEquals(top to bottom, ordered(top, bottom))
        assertEquals(top to bottom, ordered(bottom, top))
    }

    @Test
    fun `a selection of one cell has the same two ends`() {
        val cell = CellPos(row = 2, column = 5)
        assertEquals(cell to cell, ordered(cell, cell.copy()))
    }

    @Test
    fun `the origin is the first cell`() {
        assertEquals(CellPos(row = 0, column = 0), cellAt(Offset.Zero, metrics))
    }

    @Test
    fun `a point inside a cell maps to it`() {
        // x = 30 is in the fourth column (25.5 to 34), y = 40 in the third row (32 to 48).
        assertEquals(CellPos(row = 2, column = 3), cellAt(Offset(30f, 40f), metrics))
    }

    @Test
    fun `a point on the edge between two cells belongs to the next one`() {
        assertEquals(CellPos(row = 1, column = 3), cellAt(Offset(25.5f, 16f), metrics))
        assertEquals(CellPos(row = 0, column = 2), cellAt(Offset(25.49f, 15.99f), metrics))
    }

    @Test
    fun `a point left of or above the canvas maps to no cell`() {
        assertNull(cellAt(Offset(-1f, 10f), metrics))
        assertNull(cellAt(Offset(10f, -1f), metrics))
        assertNull(cellAt(Offset(-1f, -1f), metrics))
    }

    // Truncating -0.5 would give 0: the check must come before the division.
    @Test
    fun `a point just outside the canvas does not map to the first cell`() {
        assertNull(cellAt(Offset(-0.5f, 0f), metrics))
        assertNull(cellAt(Offset(0f, -0.5f), metrics))
    }

    // Dragging past the right or bottom edge keeps selecting: the selected text is clipped to the grid.
    @Test
    fun `a point past the grid maps to a cell beyond it`() {
        assertEquals(CellPos(row = 100, column = 200), cellAt(Offset(1700f, 1600f), metrics))
    }

    @Test
    fun `cells that are not measured yet map nothing`() {
        val point = Offset(10f, 10f)
        assertNull(cellAt(point, CellMetrics(width = 0f, height = 16)))
        assertNull(cellAt(point, CellMetrics(width = 8.5f, height = 0)))
        assertNull(cellAt(point, CellMetrics(width = -1f, height = 16)))
    }

    @Test
    fun `a drag keeps its anchor and moves its cursor`() {
        val selection = TerminalSelection()
        val anchor = CellPos(row = 1, column = 1)
        val cursor = CellPos(row = 0, column = 4)
        selection.start(anchor)
        assertEquals(anchor, selection.anchor)
        assertEquals(anchor, selection.cursor)
        selection.moveTo(cursor)
        assertEquals(anchor, selection.anchor)
        assertEquals(cursor, selection.cursor)
        selection.clear()
        assertNull(selection.anchor)
        assertNull(selection.cursor)
    }
}
