package org.example.kotlineditor.state

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor

/**
 * Vertical scroll of an editor: the rows on screen for drawing, mouse hits, page moves and "keep the caret visible".
 * Rows are the lines left once folded regions are collapsed. The position is counted in rows, not pixels, so zooming
 * keeps the same lines on screen; the editor reports its size and line height on every layout.
 */
@Stable
class VerticalScroll {
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

    /** First row on screen, possibly scrolled partly out at the top. */
    val firstRow: Int get() = floor(position).toInt()

    /** Pixels of [firstRow] hidden above the top edge. */
    val rowOffsetPx: Float get() = (position - firstRow) * lineHeightPx

    private val consumer =
        ScrollableState { delta ->
            val old = position
            // A positive delta scrolls forward, like a LazyListState.
            position = (position + delta / lineHeightPx).coerceIn(0f, maxPosition)
            (position - old) * lineHeightPx
        }

    /** Says when an end is reached, so that a wheel turn there neither animates nor is taken from a parent. */
    val scrollableState: ScrollableState =
        object : ScrollableState by consumer {
            override val canScrollForward: Boolean get() = position < maxPosition
            override val canScrollBackward: Boolean get() = position > 0f
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

    /** Rows that fit entirely on screen, at least one. */
    fun visibleRows(lineHeightPx: Float): Int = (viewportHeightPx / lineHeightPx).toInt().coerceAtLeast(1)

    suspend fun scrollBy(px: Float) {
        scrollableState.scrollBy(px)
    }

    /** Shows [row] at the top of the screen (as far as the content allows). */
    suspend fun scrollToRow(row: Int) {
        position = row.toFloat().coerceIn(0f, maxPosition)
    }
}
