package org.example.kotlineditor.state

import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.TextChange

/** [hint] after [change]: it follows its character; null when the change replaced the text around it. */
internal fun shiftedHint(hint: InlayHint, change: TextChange): InlayHint? {
    val pos = TextPos(hint.line, hint.column)
    val changeStart = TextPos(change.startLine, change.startColumn)
    val changeEnd = TextPos(change.endLine, change.endColumn)
    val replaced = changeStart < pos && pos < changeEnd
    val moved = shifted(pos, change)
    return hint.takeUnless { replaced }?.copy(line = moved.line, column = moved.column)
}

/** The hints of each line, to draw in the code. */
fun inlaysOf(hints: List<InlayHint>): Map<Int, LineInlays> =
    hints
        .groupBy { it.line }
        .mapValues { (_, onLine) ->
            val sorted = onLine.sortedBy { it.column }
            LineInlays(IntArray(sorted.size) { sorted[it].column }, sorted.map { it.label })
        }
