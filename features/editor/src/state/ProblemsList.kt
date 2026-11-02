package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.Diagnostic

// The list of the problems of a document, opened from the widget at the top right of the editor.

/** The problems list shown over [document]; its rows are the document's problems as they are now. */
@Stable
class ProblemsList(val document: Document) {
    /** The row chosen with the arrows. */
    var selected by mutableIntStateOf(0)
        internal set
}

/** The problems of [document], in the order of the text, the most serious first on a same position. */
fun CodeInsightState.problemsOf(document: Document): List<Diagnostic> =
    insightOf(document)
        ?.diagnostics
        .orEmpty()
        .sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }, { it.severity.ordinal }))

/** A click on the problems widget: opens the list of the problems of [document], or closes it. */
fun CodeInsightState.toggleProblems(document: Document) {
    val shown = problems?.document === document
    dismissHover()
    problems = if (shown) null else ProblemsList(document)
}

/** Up and Down in the problems list. */
fun CodeInsightState.moveProblemSelection(delta: Int) {
    val list = problems ?: return
    val count = problemsOf(list.document).size
    if (count > 0) list.selected = (list.selected + delta).mod(count)
}

/** Enter, a click on a row or on its arrow: the caret goes to the problem [index], and the list closes. */
fun CodeInsightState.goToProblem(index: Int) {
    val list = problems ?: return
    val problem = problemsOf(list.document).getOrNull(index) ?: return
    problems = null
    list.document.moveTo(TextPos(problem.range.startLine, problem.range.startColumn))
}

/** Escape, or a click beside the list. */
fun CodeInsightState.closeProblems() {
    problems = null
}
