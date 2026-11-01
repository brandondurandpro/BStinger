package org.example.kotlineditor.unit

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.VerticalScroll
import kotlin.test.Test
import kotlin.test.assertEquals

class VerticalScrollTest {
    // 100 rows of 20 px in a 200 px viewport: 10 rows on screen, the last position is row 90.
    private fun scroll() = VerticalScroll().apply { updateLayout(rows = 100, viewportHeight = 200f, lineHeight = 20f) }

    @Test
    fun `scrolling stops at the first and at the last row`() =
        runBlocking {
            val scroll = scroll()
            scroll.scrollBy(-50f)
            assertEquals(0f, scroll.position)
            scroll.scrollBy(10_000f)
            assertEquals(90f, scroll.position)
            assertEquals(10, scroll.visibleRows(20f))
        }

    @Test
    fun `pixels scrolled become rows and a partly hidden first row`() =
        runBlocking {
            val scroll = scroll()
            scroll.scrollBy(50f)
            assertEquals(2.5f, scroll.position)
            assertEquals(2, scroll.firstRow)
            assertEquals(10f, scroll.rowOffsetPx)
            assertEquals(50f, scroll.offsetPx)
        }

    @Test
    fun `zooming keeps the same row at the top`() {
        val scroll = scroll()
        scroll.scrollToPx(400f)
        assertEquals(20, scroll.firstRow)
        scroll.updateLayout(rows = 100, viewportHeight = 200f, lineHeight = 40f)
        assertEquals(20, scroll.firstRow)
        assertEquals(800f, scroll.offsetPx)
    }

    @Test
    fun `a shorter content or a taller viewport pulls the position back`() =
        runBlocking {
            val scroll = scroll()
            scroll.scrollToRow(80)
            scroll.updateLayout(rows = 50, viewportHeight = 200f, lineHeight = 20f)
            assertEquals(40f, scroll.position)
            scroll.updateLayout(rows = 5, viewportHeight = 200f, lineHeight = 20f)
            assertEquals(0f, scroll.position)
        }
}
