package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.closeProblems
import org.example.kotlineditor.state.goToProblem
import org.example.kotlineditor.state.problemsOf

private val PANEL_WIDTH = 560.dp
private val PROBLEM_ROW_HEIGHT = 26.dp
private const val VISIBLE_ROWS = 14
private const val SCRIM_ALPHA = 0.25f
private val TITLE_STYLE = TextStyle(color = IdeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
private val MESSAGE_STYLE = TextStyle(color = IdeColors.Text, fontSize = 13.sp)
private val PLACE_STYLE = TextStyle(color = IdeColors.TextSecondary, fontSize = 12.sp)

/**
 * The problems of [document] over the editor, from the widget at the top right: one row per problem, its severity,
 * message, place and source; a click on the row or on its arrow goes to it. A click beside the list closes it.
 */
@Composable
internal fun BoxScope.ProblemsListView(insight: CodeInsightState, document: Document) {
    val list = insight.problems?.takeIf { it.document === document } ?: return
    val problems = insight.problemsOf(document)
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures { insight.closeProblems() } }
            .testTag(EditorTags.PROBLEMS_SCRIM),
    )
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 32.dp, end = 12.dp)
            .width(PANEL_WIDTH)
            .popupFrame()
            .swallowClicks()
            .testTag(EditorTags.PROBLEMS_LIST),
    ) {
        BasicText(
            titleOf(problems),
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).testTag(EditorTags.PROBLEMS_LIST_TITLE),
            style = TITLE_STYLE,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        if (problems.isEmpty()) {
            BasicText(
                "No problems in this file",
                Modifier.padding(10.dp).testTag(EditorTags.PROBLEMS_LIST_EMPTY),
                style = PLACE_STYLE,
            )
        } else {
            ProblemRows(insight, problems, list.selected.coerceIn(0, problems.lastIndex))
        }
    }
}

@Composable
private fun ProblemRows(insight: CodeInsightState, problems: List<Diagnostic>, selected: Int) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val first = listState.firstVisibleItemIndex
        if (selected < first || selected >= first + VISIBLE_ROWS) listState.scrollToItem(selected)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = PROBLEM_ROW_HEIGHT * VISIBLE_ROWS), state = listState) {
        itemsIndexed(problems) { index, problem ->
            ProblemRow(
                problem,
                index == selected,
                Modifier.pointerInput(index) {
                    detectTapGestures { insight.goToProblem(index) }
                },
                index,
            ) { insight.goToProblem(index) }
        }
    }
}

@Composable
private fun ProblemRow(problem: Diagnostic, selected: Boolean, clicks: Modifier, index: Int, onGo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PROBLEM_ROW_HEIGHT)
            .background(if (selected) IdeColors.Selection else IdeColors.Popup)
            .then(clicks)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(EditorTags.problem(index))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(symbolOf(problem.severity), style = MESSAGE_STYLE.copy(color = problemColor(problem.severity)))
        BasicText(
            problem.message
                .lineSequence()
                .firstOrNull()
                .orEmpty(),
            Modifier.weight(1f).padding(start = 8.dp),
            style = MESSAGE_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val place = "${problem.range.startLine + 1}:${problem.range.startColumn + 1}"
        val source = problem.source?.let { "  $it" }.orEmpty()
        BasicText(place + source, Modifier.padding(start = 8.dp), style = PLACE_STYLE, maxLines = 1)
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(20.dp)
                .pointerInput(Unit) { detectTapGestures { onGo() } }
                .testTag(EditorTags.problemGo(index)),
            contentAlignment = Alignment.Center,
        ) { BasicText("→", style = MESSAGE_STYLE.copy(color = IdeColors.Accent)) }
    }
}

private fun titleOf(problems: List<Diagnostic>): String {
    val errors = problems.count { it.severity == DiagnosticSeverity.Error }
    val others = problems.size - errors
    return "Problems: $errors ${plural(errors, "error")}, $others ${plural(others, "warning")}"
}

private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"

private fun symbolOf(severity: DiagnosticSeverity): String =
    when (severity) {
        DiagnosticSeverity.Error -> "●"
        DiagnosticSeverity.Warning -> "▲"
        else -> "ⓘ"
    }
