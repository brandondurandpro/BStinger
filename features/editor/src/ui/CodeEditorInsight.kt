package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import org.example.kotlineditor.model.UsageCount
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.LinkTarget
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.applyCodeAction
import org.example.kotlineditor.state.closeProblems
import org.example.kotlineditor.state.goToProblem
import org.example.kotlineditor.state.moveProblemSelection
import org.example.kotlineditor.state.toggleProblems

// What code intelligence adds to the code editor: the popups over the code, the mouse, the keys of its lists.

/** The marks of [document] from its language server, indexed once per change. */
@Composable
internal fun rememberInsightMarks(insight: CodeInsightState?, document: Document, link: LinkTarget?): InsightMarks {
    val shown = insight?.insightOf(document)
    return remember(shown?.diagnostics, shown?.usageCounts, shown?.highlights, link) {
        if (shown == null) InsightMarks.None else InsightMarks(shown, link, document)
    }
}

/** The popups, the problems widget, and what follows the caret and the text. */
@Composable
internal fun BoxScope.InsightLayer(
    insight: CodeInsightState,
    path: String,
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    focusRequester: FocusRequester,
    viewport: () -> IntSize,
) {
    FollowInsight(insight, path, document)
    CodeInsightOverlays(insight, tab, document, metrics, focusRequester, viewport)
    ProblemsListView(insight, document)
    insight.insightOf(document)?.let { shown ->
        val toggle = { insight.toggleProblems(document) }
        ProblemsWidget(shown, toggle, Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
    }
}

// The occurrences and the parameter info follow the caret and the text; what is shown goes with the editor.
@Composable
private fun FollowInsight(insight: CodeInsightState, path: String, document: Document) {
    LaunchedEffect(document) {
        snapshotFlow { document.caret to document.version }.collect { insight.caretMoved(path, document) }
    }
    LaunchedEffect(document) {
        snapshotFlow { document.version }.drop(1).collect { insight.edited(path, document) }
    }
    DisposableEffect(document) {
        onDispose {
            if (insight.hover?.document === document) insight.dismissHover()
            if (insight.signature?.document === document) insight.dismissSignature()
        }
    }
}

/** The mouse over the text: documentation after a pause, the word under it as a link with Ctrl held. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.hoverInsight(
    insight: CodeInsightState?,
    path: String?,
    document: Document,
    tab: EditorTab,
    metrics: EditorMetrics,
): Modifier {
    if (insight == null || path == null) return this
    val moved: AwaitPointerEventScope.(PointerEvent) -> Unit = { event ->
        val change = event.changes.firstOrNull()
        if (change != null && !change.pressed) {
            val modifiers = event.keyboardModifiers
            val ctrl = modifiers.isCtrlPressed || modifiers.isMetaPressed
            val pos = metrics.cellAt(change.position, tab.verticalScroll, tab.horizontalScroll, document)
            insight.mouseAt(path, document, pos, ctrl)
        }
    }
    // The first event of a mouse coming over the editor is an Enter, already at a position.
    return onPointerEvent(PointerEventType.Enter, onEvent = moved)
        .onPointerEvent(PointerEventType.Move, onEvent = moved)
        .onPointerEvent(PointerEventType.Exit) { insight.mouseLeft() }
}

/** What a click on a usage hint acts on. */
internal class HintTarget(
    val insight: CodeInsightState?,
    val path: String?,
    val document: Document,
    val marks: InsightMarks,
)

/** The declaration whose "3 usages" hint is at [offset] (on the row of [pos]), null when the mouse is not on one. */
internal fun hintAt(
    target: HintTarget,
    metrics: EditorMetrics,
    tab: EditorTab,
    pos: TextPos,
    offset: Offset,
): UsageCount? {
    val insight = target.insight ?: return null
    val hint = target.marks.hints[pos.line]
    val document = target.document
    val declaration = insight.insightOf(document)?.usageCounts?.firstOrNull { it.range.startLine == pos.line }
    val line = document.lines[pos.line]
    val left = metrics.gutterPx + metrics.paddingPx - tab.horizontalScroll.offset + hintColumn(line) * metrics.charWidth
    val right = left + (hint?.length ?: 0) * metrics.charWidth
    val onHint = hint != null && target.path != null && offset.x in left..right
    return declaration?.takeIf { onHint }
}

/** A click on the "3 usages" hint of a line shows the usages of its declaration; true when it was on one. */
internal fun hintClicked(
    target: HintTarget,
    metrics: EditorMetrics,
    tab: EditorTab,
    pos: TextPos,
    offset: Offset,
): Boolean {
    val declaration = hintAt(target, metrics, tab, pos, offset)
    val start = declaration?.let { TextPos(it.range.startLine, it.range.startColumn) }
    val path = target.path
    if (start != null && path != null) target.insight?.showUsages(path, target.document, start)
    return start != null
}

/** Releasing Ctrl ends the link under the mouse. */
internal fun ctrlKey(event: KeyEvent, insight: CodeInsightState) {
    val ctrl = event.key == Key.CtrlLeft || event.key == Key.CtrlRight
    if (ctrl && event.type == KeyEventType.KeyUp) insight.ctrlReleased()
}

/** The keys of the lists of places and quick fixes while one is shown, and Escape for whatever is shown. */
internal fun insightKey(event: KeyEvent, insight: CodeInsightState, document: Document): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val listShown = insight.locations?.document === document || insight.codeActions?.document === document
    val plain = !event.isCtrlPressed && !event.isAltPressed
    val list = down && listShown && plain
    val problems = down && plain && insight.problems?.document === document
    return when {
        problems -> problemsKey(event.key, insight)
        list -> listKey(event.key, insight)
        down && event.key == Key.Escape -> insight.escape()
        else -> false
    }
}

// The problems list owns the arrows, Enter and Escape; other keys still edit the code below.
private fun problemsKey(key: Key, insight: CodeInsightState): Boolean {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    when {
        key == Key.DirectionDown -> insight.moveProblemSelection(1)
        key == Key.DirectionUp -> insight.moveProblemSelection(-1)
        enter -> insight.problems?.let { insight.goToProblem(it.selected) }
        key == Key.Escape -> insight.closeProblems()
        else -> return false
    }
    return true
}

private fun listKey(key: Key, insight: CodeInsightState): Boolean {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    when {
        key == Key.DirectionDown -> insight.moveSelection(1)
        key == Key.DirectionUp -> insight.moveSelection(-1)
        enter && insight.locations != null -> insight.openLocation()
        enter -> insight.applyCodeAction()
        key == Key.Escape -> insight.escape()
        else -> return false
    }
    return true
}

/** Ctrl+W, which the language server's selection ranges answer in a file it serves. */
internal fun isExtendSelection(event: KeyEvent): Boolean {
    val plainCtrl = event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed
    return event.type == KeyEventType.KeyDown && plainCtrl && event.key == Key.W
}
