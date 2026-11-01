package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.syntax.Language

/**
 * Code folding of one document. A region spans from the line opening a `{` (or a block comment) to the line
 * closing it; folding it hides the lines after its start. Rows are what the editor displays, lines are document
 * lines: without any fold both are the same and no mapping is allocated.
 */
@Stable
class FoldingModel(private val document: Document) {
    /** Start line -> end line of every foldable region, recomputed in the background after edits. */
    var regions: Map<Int, Int> by mutableStateOf(emptyMap())

    var folded: Set<Int> by mutableStateOf(emptySet())
        internal set

    private var foldedForLineCount = -1

    // Visible document lines in display order, null when nothing is folded (identity mapping).
    private val visibleLines: IntArray? by derivedStateOf {
        if (folded.isEmpty()) null else visibleLines(folded, regions, document.lineCount)
    }

    fun rowCount(): Int = visibleLines?.size ?: document.lineCount

    fun lineAt(row: Int): Int = visibleLines?.let { it[row.coerceIn(0, it.lastIndex)] } ?: row

    fun rowOf(line: Int): Int {
        val index = visibleLines?.binarySearch(line) ?: line
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }

    fun isFoldable(line: Int): Boolean = line in regions

    fun isFolded(line: Int): Boolean = line in folded

    fun isHidden(line: Int): Boolean = folded.any { start -> line in start + 1..(regions[start] ?: start) }

    fun fold(line: Int) {
        val end = regions[line] ?: return
        foldedForLineCount = document.lineCount
        folded = folded + line
        // The caret cannot stay inside a folded region: move it to the region's first line.
        if (document.caret.line in line + 1..end) document.moveTo(TextPos(line, document.lines[line].length))
    }

    fun unfold(line: Int) {
        folded = folded - line
    }

    fun foldAll() {
        foldedForLineCount = document.lineCount
        folded = regions.keys.toSet()
        val caretLine = document.caret.line
        folded.filter { caretLine in it + 1..regions.getValue(it) }.minOrNull()?.let {
            document.moveTo(TextPos(it, document.lines[it].length))
        }
    }

    fun unfoldAll() {
        folded = emptySet()
    }

    /**
     * Folds are line-based: once lines are inserted or removed they would point to the wrong code, so they are
     * dropped (regions themselves are recomputed by the editor).
     */
    fun onEdit() {
        if (folded.isNotEmpty() && document.lineCount != foldedForLineCount) folded = emptySet()
    }

    companion object {
        /** Foldable regions of [lines] (start line -> end line), see [FoldRegions]. */
        fun computeRegions(lines: List<String>, language: Language): Map<Int, Int> =
            if (language.slashComments) {
                FoldRegions().also { scanner -> lines.forEachIndexed(scanner::scan) }.regions
            } else {
                emptyMap()
            }
    }
}

fun FoldingModel.toggle(line: Int) = if (isFolded(line)) unfold(line) else fold(line)

/** Ctrl+-: folds the innermost unfolded region containing [line]. */
fun FoldingModel.foldAt(line: Int) {
    regionContaining(line) { it !in folded }?.let(::fold)
}

/** Ctrl+=: unfolds the region starting on [line], or the innermost folded one containing it. */
fun FoldingModel.unfoldAt(line: Int) {
    val start = if (line in folded) line else regionContaining(line) { it in folded }
    start?.let(::unfold)
}

/** Unfolds whatever hides [line] (the caret moved there, e.g. by a search). */
fun FoldingModel.reveal(line: Int) {
    if (folded.isNotEmpty()) folded = folded.filterTo(HashSet()) { line !in it + 1..(regions[it] ?: it) }
}

/** Text shown after a folded line, e.g. `...}` (the trimmed closing line). */
fun FoldingModel.placeholder(line: Int, lines: List<String>): String =
    regions[line]?.let { "..." + lines.getOrNull(it)?.trim().orEmpty() }.orEmpty()

private inline fun FoldingModel.regionContaining(line: Int, accept: (Int) -> Boolean): Int? =
    regions.entries
        .filter { (start, end) -> line in start..end && accept(start) }
        .maxByOrNull { it.key }
        ?.key

private fun visibleLines(folded: Set<Int>, regions: Map<Int, Int>, count: Int): IntArray {
    val out = IntArray(count)
    var n = 0
    var line = 0
    while (line < count) {
        out[n++] = line
        val end = if (line in folded) regions[line] else null
        line = if (end != null && end > line) end + 1 else line + 1
    }
    return out.copyOf(n)
}
