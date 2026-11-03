package org.example.kotlineditor.ui

import androidx.compose.runtime.Immutable
import org.example.kotlineditor.model.CodeLensAction
import org.example.kotlineditor.model.CodeLensInfo
import org.example.kotlineditor.state.ScreenLine

private const val LENS_GAP_COLUMNS = 2

/** A code lens on a line: its place among the lenses of the document, its text, and whether a click does something. */
@Immutable
internal class LineLens(val index: Int, val title: String, val clickable: Boolean)

/** The code lenses of each line, in document order. */
internal fun lensesByLine(lenses: List<CodeLensInfo>): Map<Int, List<LineLens>> =
    lenses.withIndex().groupBy({ it.value.range.startLine }) { (index, lens) ->
        LineLens(index, lens.title, lens.action != CodeLensAction.None)
    }

/** Where each lens of [line] starts, in visual columns: after its usage [hint] (or its text), two cells apart. */
internal fun lensColumns(line: ScreenLine, hint: String?, lenses: List<LineLens>): IntArray {
    val afterHint = hint?.let { it.length + LENS_GAP_COLUMNS } ?: 0
    var column = hintColumn(line) + afterHint
    return IntArray(lenses.size) { at ->
        val start = column
        column += lenses[at].title.length + LENS_GAP_COLUMNS
        start
    }
}
