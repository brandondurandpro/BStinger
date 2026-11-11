package org.example.kotlineditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** A cell position in the grid, used for the selection. */
internal data class CellPos(val row: Int, val column: Int)

/** Drag selection over the visible grid: the cell where the drag started and the one under the pointer. */
internal class TerminalSelection {
    var anchor by mutableStateOf<CellPos?>(null)
        private set

    var cursor by mutableStateOf<CellPos?>(null)
        private set

    fun start(at: CellPos?) {
        anchor = at
        cursor = at
    }

    fun moveTo(at: CellPos?) {
        cursor = at
    }

    fun clear() {
        anchor = null
        cursor = null
    }
}

/** The two ends of a selection, top-left first. */
internal fun ordered(a: CellPos, b: CellPos): Pair<CellPos, CellPos> {
    if (a.row != b.row) return if (a.row < b.row) a to b else b to a
    return if (a.column <= b.column) a to b else b to a
}

/** Cell under [at], or `null` when the point lies outside the canvas. */
internal fun cellAt(at: Offset, metrics: CellMetrics): CellPos? {
    val validCell = metrics.width > 0f && metrics.height > 0
    val inside = at.x >= 0f && at.y >= 0f
    return if (validCell && inside) CellPos((at.y / metrics.height).toInt(), (at.x / metrics.width).toInt()) else null
}
