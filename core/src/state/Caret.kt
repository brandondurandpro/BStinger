package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import org.example.kotlineditor.model.TextChange

/**
 * One caret: its position, the other end of its selection ([anchor], null when nothing is selected) and the screen
 * column it keeps when moving vertically ([preferredColumn], negative when it has none).
 */
@Immutable
data class Caret(val pos: TextPos, val anchor: TextPos? = null, val preferredColumn: Int = -1) {
    val hasSelection: Boolean get() = anchor != null && anchor != pos

    /** Start of the selection, or the caret. */
    val start: TextPos get() = anchor?.let { minOf(it, pos) } ?: pos

    /** End of the selection, or the caret. */
    val end: TextPos get() = anchor?.let { maxOf(it, pos) } ?: pos
}

internal fun Caret.clamped(document: Document): Caret =
    Caret(document.clamp(pos), anchor?.let(document::clamp), preferredColumn)

/** [this] caret where it is after [change]: it keeps its place in the text around it. */
internal fun Caret.mappedThrough(change: TextChange): Caret {
    val newPos = pos.mappedThrough(change)
    val newAnchor = anchor?.mappedThrough(change)
    val moved = newPos != pos || newAnchor != anchor
    return if (moved) Caret(newPos, newAnchor) else this
}

internal fun TextPos.mappedThrough(change: TextChange): TextPos {
    val start = TextPos(change.startLine, change.startColumn)
    val end = TextPos(change.endLine, change.endColumn)
    val newEnd = endOf(start, change.text)
    return when {
        this <= start -> this
        this < end -> newEnd
        line == end.line -> TextPos(newEnd.line, newEnd.column + column - end.column)
        newEnd.line == end.line -> this
        else -> TextPos(line + newEnd.line - end.line, column)
    }
}

/**
 * [carets] without duplicates: carets at the same place, or whose selections overlap, become one, which takes the
 * place in the list of the first of them (the primary one first, see [Document.setCarets]).
 */
internal fun mergeCarets(carets: List<Caret>): List<Caret> {
    val sorted = carets.withIndex().sortedBy { it.value.start }
    val merged = ArrayList<IndexedValue<Caret>>()
    for (entry in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && touches(previous.value, entry.value)) {
            val first = minOf(previous.index, entry.index)
            merged[merged.lastIndex] = IndexedValue(first, union(previous.value, entry.value))
        } else {
            merged += entry
        }
    }
    return merged.sortedBy { it.index }.map { it.value }
}

private fun touches(previous: Caret, next: Caret): Boolean {
    val samePlace = previous.start == next.start
    val overlap = next.start < previous.end
    val emptyTouch = next.start == previous.end && (!previous.hasSelection || !next.hasSelection)
    return samePlace || overlap || emptyTouch
}

// The caret covering both selections, its caret on the side the later one had it.
private fun union(previous: Caret, next: Caret): Caret {
    if (!previous.hasSelection && !next.hasSelection) return next
    val start = minOf(previous.start, next.start)
    val end = maxOf(previous.end, next.end)
    val forward = next.anchor == null || next.pos >= next.anchor
    return if (forward) Caret(end, start) else Caret(start, end)
}
