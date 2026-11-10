package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.state.DocumentInsight

private val WIDGET_SHAPE = IdeShapes.Medium
private val COUNT_STYLE get() = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.Text, fontSize = 12.sp)
private const val WIDGET_ALPHA = 0.85f

/** The problems of the document at the top right, like IntelliJ's: a click lists them (F2 goes to the next one). */
@Composable
internal fun ProblemsWidget(insight: DocumentInsight, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val errors = insight.errorCount
    val warnings = insight.warningCount
    Row(
        modifier
            .padding(6.dp)
            .background(IdeColors.Editor.copy(alpha = WIDGET_ALPHA), WIDGET_SHAPE)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(EditorTags.PROBLEMS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (errors == 0 && warnings == 0) {
            CheckIcon(Modifier.size(14.dp), IdeColors.GitAdded)
        }
        if (errors > 0) Counter("●", errors, DiagnosticSeverity.Error, EditorTags.PROBLEMS_ERRORS)
        if (warnings > 0) Counter("▲", warnings, DiagnosticSeverity.Warning, EditorTags.PROBLEMS_WARNINGS)
    }
}

@Composable
private fun Counter(symbol: String, count: Int, severity: DiagnosticSeverity, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(symbol, style = COUNT_STYLE.copy(color = problemColor(severity), fontSize = 11.sp))
        BasicText(
            count.toString(),
            Modifier.padding(start = 3.dp).testTag(tag),
            style = COUNT_STYLE,
        )
    }
}
