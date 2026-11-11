package org.example.kotlineditor.unit

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.StickyHeader
import org.example.kotlineditor.state.TreeOutline
import org.example.kotlineditor.state.TreeRow
import org.example.kotlineditor.state.TreeScroll
import org.example.kotlineditor.state.stickyHeaders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val H = 10

class StickyTreeTest {
    // 0 root / 1 a / 2 a/x / 3 a/y / 4 b / 5 b/c / 6 b/c/1 / 7 b/c/2 / 8 b/z / 9 f
    private val outline =
        TreeOutline(
            listOf(
                dir("root", 0),
                dir("a", 1),
                file("a/x", 2),
                file("a/y", 2),
                dir("b", 1),
                dir("b/c", 2),
                file("b/c/1", 3),
                file("b/c/2", 3),
                file("b/z", 2),
                file("f", 1),
            ),
        )

    private fun dir(path: String, depth: Int) = TreeRow(FileEntry(path, path, true), depth, true, false)

    private fun file(path: String, depth: Int) = TreeRow(FileEntry(path, path, false), depth, false, false)

    private fun headers(first: Int, offset: Int, max: Int = 10) =
        stickyHeaders(outline, TreeScroll(first, offset, H), max)

    @Test
    fun `outline knows the parent and the end of every row`() {
        assertContentEquals(intArrayOf(-1, 0, 1, 1, 0, 4, 5, 5, 4, 0), outline.parent)
        assertContentEquals(intArrayOf(10, 4, 3, 4, 9, 8, 7, 8, 9, 10), outline.end)
    }

    @Test
    fun `nothing is pinned before scrolling`() {
        assertTrue(headers(0, 0).isEmpty())
    }

    @Test
    fun `a directory whose top is scrolled away is pinned with its ancestors`() {
        assertEquals(listOf(StickyHeader(0, 0), StickyHeader(1, 10)), headers(0, 5))
        assertEquals(listOf(StickyHeader(0, 0), StickyHeader(4, 10), StickyHeader(5, 20)), headers(3, 5))
    }

    @Test
    fun `the ancestors of the row under each slot are pinned`() {
        assertEquals(listOf(StickyHeader(0, 0), StickyHeader(1, 10)), headers(2, 0))
        // b is fully visible under the root header: it is not pinned.
        assertEquals(listOf(StickyHeader(0, 0)), headers(3, 0))
    }

    @Test
    fun `the end of a directory pushes its header up`() {
        assertEquals(listOf(StickyHeader(0, 0), StickyHeader(1, 5)), headers(2, 5))
    }

    @Test
    fun `the number of headers is capped`() {
        assertEquals(listOf(StickyHeader(0, 0)), headers(3, 5, max = 1))
    }

    @Test
    fun `an empty tree or a zero row height has no headers`() {
        assertTrue(stickyHeaders(TreeOutline(emptyList()), TreeScroll(0, 0, H), 10).isEmpty())
        assertTrue(stickyHeaders(outline, TreeScroll(3, 5, 0), 10).isEmpty())
    }
}
