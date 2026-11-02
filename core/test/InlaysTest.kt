package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals

class InlaysTest {
    // `f(1, 22)` with `a: ` before 1 and `bb: ` before 22.
    private val line = "f(1, 22)"
    private val inlays = LineInlays(intArrayOf(2, 5), listOf("a: ", "bb: "))

    @Test
    fun `a column is after the hints drawn before its character`() {
        assertEquals(0, visualColumn(line, 0, inlays))
        assertEquals(2 + 3, visualColumn(line, 2, inlays))
        assertEquals(4 + 3, visualColumn(line, 4, inlays))
        assertEquals(5 + 3 + 4, visualColumn(line, 5, inlays))
        assertEquals(line.length + 7, visualColumn(line, line.length, inlays))
    }

    @Test
    fun `a cell of a hint is the column of the character it precedes`() {
        assertEquals(1, columnForVisual(line, 1, inlays))
        // `(` ends at cell 2, then `a: ` covers cells 2 to 4, then `1` starts at 5.
        assertEquals(2, columnForVisual(line, 2, inlays))
        assertEquals(2, columnForVisual(line, 4, inlays))
        assertEquals(2, columnForVisual(line, 5, inlays))
        assertEquals(3, columnForVisual(line, 6, inlays))
        assertEquals(5, columnForVisual(line, 9, inlays))
        assertEquals(line.length, columnForVisual(line, 100, inlays))
    }

    @Test
    fun `without hints the columns are those of the text`() {
        for (column in 0..line.length) {
            assertEquals(column, visualColumn(line, column, null))
            assertEquals(column, columnForVisual(line, column, null))
        }
    }

    @Test
    fun `a range ends before the hints of the character after it`() {
        val screen = ScreenLine(line, inlays)
        // `1, ` is columns 2 to 5: it ends before `bb: `.
        assertEquals(5, screen.startOf(2))
        assertEquals(5 + 3, screen.endOf(5))
        assertEquals(line.length + 7, screen.end)
    }
}
