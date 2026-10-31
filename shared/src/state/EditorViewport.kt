package org.example.kotlineditor.state

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor

/**
 * The rows of an editor shown on screen, whatever draws them: mouse hits, page moves and "keep the caret visible"
 * go through it. Rows are the lines left once folded regions are collapsed.
 */
interface EditorViewport {
    /** First row on screen, possibly scrolled partly out at the top. */
    val firstRow: Int

    /** Pixels of [firstRow] hidden above the top edge. */
    val rowOffsetPx: Float

    /** Rows that fit entirely on screen, at least one. */
    fun visibleRows(lineHeightPx: Float): Int

    suspend fun scrollBy(px: Float)

    /** Shows [row] at the top of the screen (as far as the content allows). */
    suspend fun scrollToRow(row: Int)
}

/** The rows of a `LazyColumn`, one item per row. */
class LazyListViewport(private val listState: LazyListState) : EditorViewport {
    override val firstRow: Int get() = listState.firstVisibleItemIndex
    override val rowOffsetPx: Float get() = listState.firstVisibleItemScrollOffset.toFloat()

    override fun visibleRows(lineHeightPx: Float): Int =
        (listState.layoutInfo.viewportSize.height / lineHeightPx).toInt().coerceAtLeast(1)

    override suspend fun scrollBy(px: Float) {
        listState.scrollBy(px)
    }

    override suspend fun scrollToRow(row: Int) = listState.scrollToItem(row)
}

/**
 * Vertical scroll of an editor drawing its rows itself. The position is counted in rows, not pixels, so zooming
 * keeps the same lines on screen; the editor reports its size and line height on every layout.
 */
@Stable
class VerticalScroll : EditorViewport {
    /** Row at the top edge, the fraction being how much of it is scrolled out. */
    var position by mutableFloatStateOf(0f)
        private set
    var rowCount by mutableIntStateOf(0)
        private set
    var viewportHeightPx by mutableFloatStateOf(0f)
        private set
    var lineHeightPx by mutableFloatStateOf(1f)
        private set

    /** Last position: the last row at the bottom edge, like a list scrolled to its end. */
    val maxPosition: Float
        get() = (rowCount - viewportHeightPx / lineHeightPx).coerceAtLeast(0f)

    override val firstRow: Int get() = floor(position).toInt()
    override val rowOffsetPx: Float get() = (position - firstRow) * lineHeightPx

    val scrollableState =
        ScrollableState { delta ->
            val old = position
            // A positive delta scrolls forward, like a LazyListState.
            position = (position + delta / lineHeightPx).coerceIn(0f, maxPosition)
            (position - old) * lineHeightPx
        }

    /** Scroll offset in pixels, for the scrollbar. */
    val offsetPx: Float get() = position * lineHeightPx
    val contentHeightPx: Float get() = rowCount * lineHeightPx

    fun updateLayout(rows: Int, viewportHeight: Float, lineHeight: Float) {
        rowCount = rows
        viewportHeightPx = viewportHeight
        lineHeightPx = lineHeight.coerceAtLeast(1f)
        position = position.coerceIn(0f, maxPosition)
    }

    fun scrollToPx(px: Float) {
        position = (px / lineHeightPx).coerceIn(0f, maxPosition)
    }

    override fun visibleRows(lineHeightPx: Float): Int = (viewportHeightPx / lineHeightPx).toInt().coerceAtLeast(1)

    override suspend fun scrollBy(px: Float) {
        scrollableState.scrollBy(px)
    }

    override suspend fun scrollToRow(row: Int) {
        position = row.toFloat().coerceIn(0f, maxPosition)
    }
}
