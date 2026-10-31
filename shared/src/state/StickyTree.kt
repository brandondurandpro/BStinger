package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable

/**
 * Parent and subtree end of every row of the flattened tree, computed once per change of the rows (O(n)), so that
 * the sticky headers can be found in O(depth) on every scroll frame, even in a directory of thousands of files.
 */
class TreeOutline(val rows: List<TreeRow>) {
    /** Index of the parent row, -1 for the root. */
    val parent = IntArray(rows.size) { -1 }

    /** Index just after the last descendant of the row. */
    val end = IntArray(rows.size) { it + 1 }

    init {
        val stack = IntArray(rows.size)
        var top = 0
        for (i in rows.indices) {
            val depth = rows[i].depth
            while (top > 0 && rows[stack[top - 1]].depth >= depth) end[stack[--top]] = i
            if (top > 0) parent[i] = stack[top - 1]
            stack[top++] = i
        }
        while (top > 0) end[stack[--top]] = rows.size
    }

    val size: Int get() = rows.size
}

/** A directory row pinned at the top of the tree, drawn [y] pixels below the top of the viewport. */
@Immutable
data class StickyHeader(val index: Int, val y: Int)

/** Where the tree list is scrolled: the first visible row, how many pixels of it are hidden, the row height. */
class TreeScroll(val firstIndex: Int, val firstOffset: Int, val rowHeight: Int)

/**
 * The directories whose names stay pinned at the top of the tree, like IntelliJ's sticky lines: the header of slot
 * `k` is the ancestor of depth `k` of the row drawn under that slot (or the row itself when it is an expanded
 * directory whose top is scrolled away). A header is pushed up when the end of its directory reaches it.
 */
fun stickyHeaders(outline: TreeOutline, scroll: TreeScroll, maxHeaders: Int): List<StickyHeader> {
    val h = scroll.rowHeight
    if (h <= 0 || outline.size == 0) return emptyList()
    val headers = ArrayList<Int>()
    var next = nextHeader(outline, scroll, headers)
    while (next >= 0 && headers.size < maxHeaders) {
        headers += next
        next = nextHeader(outline, scroll, headers)
    }
    return headers.mapIndexed { slot, index ->
        StickyHeader(index, minOf(slot * h, scroll.top(outline.end[index]) - h))
    }
}

private fun TreeScroll.top(index: Int) = (index - firstIndex) * rowHeight - firstOffset

// The header of the next slot, child of the previous header, or -1 when there is none.
private fun nextHeader(outline: TreeOutline, scroll: TreeScroll, headers: List<Int>): Int {
    val slotTop = headers.size * scroll.rowHeight
    val index = scroll.firstIndex + (scroll.firstOffset + slotTop) / scroll.rowHeight
    if (index >= outline.size) return -1
    val self = scroll.top(index) < slotTop && outline.rows[index].expanded && outline.end[index] > index + 1
    var candidate = if (self) index else outline.parent[index]
    while (candidate >= 0 && outline.rows[candidate].depth > headers.size) candidate = outline.parent[candidate]
    val valid =
        candidate >= 0 &&
            outline.rows[candidate].depth == headers.size &&
            (headers.isEmpty() || outline.parent[candidate] == headers.last())
    return if (valid) candidate else -1
}
