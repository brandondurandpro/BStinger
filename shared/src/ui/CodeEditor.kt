package org.example.kotlineditor.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.EditorViewport
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.state.LazyListViewport
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.identifierAt
import org.example.kotlineditor.state.matchingBracket
import org.example.kotlineditor.state.placeholder
import org.example.kotlineditor.state.reveal
import org.example.kotlineditor.state.visualColumn
import org.example.kotlineditor.state.wordOccurrences

private const val CARET_BLINK_MILLIS = 530L
private const val CARET_MARGIN_COLUMNS = 4
private const val RIGHT_EDGE_COLUMNS = 2
private const val RIGHT_SCROLL_COLUMNS = 6

/** Search occurrences to paint in the editor, grouped by line (visual columns). */
@Immutable
class SearchHighlights(val byLine: Map<Int, IntArray>, val length: Int, val current: TextPos?)

/** Hints computed once per caret move and painted on the visible lines. */
@Immutable
internal class CaretHints(val identifier: String?, val brackets: Pair<TextPos, TextPos>?)

/**
 * Virtualized code editor: only visible rows are composed, horizontal scrolling is read in the layout phase, and
 * highlighting is computed per visible line. Keys go through the IntelliJ keymap of `EditorKeymap.kt`.
 */
@Composable
fun CodeEditor(
    tab: EditorTab,
    document: Document,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    highlights: SearchHighlights? = null,
    fontSize: Float = AppState.DEFAULT_EDITOR_FONT_SIZE,
    onZoom: (delta: Float) -> Unit = {},
    onCtrlClick: (TextPos) -> Unit = {},
    canvas: Boolean = true,
) {
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    val caretOn = rememberCaretBlink(document, focused)
    val metrics = rememberEditorMetrics(document.lineCount, fontSize, tab.blameVisible)
    val hints by remember(document) {
        derivedStateOf {
            val identifier = if (document.hasSelection) null else document.identifierAt(document.caret)
            CaretHints(identifier, document.matchingBracket())
        }
    }
    val scope = rememberCoroutineScope()
    val clicks = remember { ClickCounter() }
    val viewport: EditorViewport = if (canvas) tab.verticalScroll else remember(tab) { LazyListViewport(tab.listState) }
    if (canvas) {
        val rows = document.folding.rowCount()
        SideEffect { tab.verticalScroll.updateLayout(rows, viewportHeight, metrics.lineHeightPx) }
    }
    tab.horizontalScroll.updateRange(metrics.maxHorizontalOffset(document.maxLineLength, viewportWidth))
    FollowCaret(tab, viewport, document, metrics) { viewportWidth }
    val onSize: (IntSize) -> Unit = {
        viewportWidth = it.width.toFloat()
        viewportHeight = it.height.toFloat()
    }
    Box(
        modifier
            .background(IdeColors.Editor)
            .testTag(Tags.EDITOR)
            .onSizeChanged(onSize)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { handleEditorKey(it, document, viewport.visibleRows(metrics.lineHeightPx)) }
            .focusable()
            .scrollable(tab.horizontalScroll.scrollableState, Orientation.Horizontal)
            .verticalScrollWhen(canvas, tab)
            .zoomOnCtrlWheel(onZoom),
    ) {
        // Text cursor and mouse selection only over the text: the scrollbars are siblings drawn on top, so pressing
        // them never reaches this box (siblings do not share pointer events) and never selects text.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(Tags.EDITOR_TEXT)
                .pointerHoverIcon(PointerIcon.Text)
                .pointerInput(document) {
                    val target =
                        GestureTarget(document, tab, viewport, metrics, clicks, focusRequester, onCtrlClick) { delta ->
                            scope.launch { viewport.scrollBy(delta) }
                        }
                    awaitEachGesture { editorGesture(target) }
                },
        ) {
            if (canvas) {
                EditorCanvas(tab, document, metrics, highlights, hints, caretOn)
            } else {
                EditorRows(tab, document, metrics, highlights, hints, caretOn)
            }
        }
        EditorScrollbars(tab, metrics, canvas) { viewportWidth }
    }
}

@Composable
private fun EditorRows(
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    highlights: SearchHighlights?,
    hints: CaretHints,
    caretOn: State<Boolean>,
) {
    val folding = document.folding
    val changes =
        remember(tab.lineChanges) {
            tab.lineChanges.filter { it.kind != GitChangeKind.Deleted }.associate { it.line to it.kind }
        }
    val deletions =
        remember(tab.lineChanges) {
            tab.lineChanges.filter { it.kind == GitChangeKind.Deleted }.map { it.line }
        }
    val blame = remember(tab.blame) { tab.blame?.associateBy { it.line } }
    LazyColumn(Modifier.fillMaxSize(), state = tab.listState) {
        items(count = folding.rowCount(), contentType = { 0 }) { row ->
            val index = folding.lineAt(row)
            val line = document.lines[index]
            val folded = folding.isFolded(index)
            val source =
                LineSource(
                    index,
                    line,
                    document.language,
                    document.commentStates?.getOrNull(index) == true,
                    folding.isFoldable(index),
                    folded,
                    if (folded) folding.placeholder(index, document.lines) else null,
                )
            val annotation = blame?.get(index)
            val deletedAtEnd = index == document.lines.lastIndex && document.lineCount in deletions
            CodeLine(
                source,
                decorations(
                    document,
                    index,
                    line,
                    highlights,
                    hints,
                    changes[index],
                    index in deletions || deletedAtEnd,
                    annotation?.let { "${it.author} ${it.date}" },
                ),
                metrics,
                tab.horizontalScroll,
                caretOn,
            )
        }
    }
}

internal fun decorations(
    document: Document,
    index: Int,
    line: String,
    highlights: SearchHighlights?,
    hints: CaretHints,
    change: GitChangeKind?,
    deleted: Boolean,
    blame: String?,
): LineDecorations {
    val caret = document.caret
    val selection = document.selectionRange()?.takeIf { index in it.first.line..it.second.line }
    val selectionStart = selection?.let { if (index == it.first.line) visualColumn(line, it.first.column) else 0 } ?: -1
    val selectionEnd =
        selection?.let {
            if (index == it.second.line) {
                visualColumn(line, it.second.column)
            } else {
                visualColumn(line, line.length) + 1
            }
        } ?: -1
    val word = hints.identifier
    val bracketColumns =
        hints.brackets
            ?.toList()
            ?.filter { it.line == index }
            ?.map { visualColumn(line, it.column) }
    return LineDecorations(
        caretColumn = if (caret.line == index) visualColumn(line, caret.column) else -1,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        matches = highlights?.byLine?.get(index),
        matchLength = highlights?.length ?: 0,
        currentMatch = highlights?.current?.takeIf { it.line == index }?.let { visualColumn(line, it.column) } ?: -1,
        brackets = bracketColumns?.takeIf { it.isNotEmpty() }?.toIntArray(),
        usages = word?.let { wordOccurrences(line, it) }?.map { visualColumn(line, it) }?.toIntArray(),
        usageLength = word?.length ?: 0,
        change = change,
        deleted = deleted,
        blame = blame,
    )
}

// Ctrl+wheel zooms, intercepted before the list scrolls.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.zoomOnCtrlWheel(onZoom: (Float) -> Unit) =
    onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
            if (delta != 0.0) onZoom(if (delta < 0) 1f else -1f)
            event.changes.forEach { it.consume() }
        }
    }

// Caret blinking, reset whenever the caret moves. Infinite-animation frames are disabled in UI tests.
@Composable
private fun rememberCaretBlink(document: Document, focused: Boolean): State<Boolean> {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(document, focused) {
        on.value = focused
        if (focused) blink(document, on)
    }
    return on
}

private suspend fun blink(document: Document, on: MutableState<Boolean>) {
    var resetAt = withInfiniteAnimationFrameMillis { it }
    var lastCaret = document.caret
    while (true) {
        withInfiniteAnimationFrameMillis { now ->
            if (document.caret != lastCaret) {
                lastCaret = document.caret
                resetAt = now
            }
            on.value = (now - resetAt) / CARET_BLINK_MILLIS % 2 == 0L
        }
    }
}

// Keeps the caret on screen after keyboard moves and programmatic jumps.
@Composable
private fun FollowCaret(
    tab: EditorTab,
    viewport: EditorViewport,
    document: Document,
    metrics: EditorMetrics,
    viewportWidth: () -> Float,
) {
    LaunchedEffect(document) {
        // drop(1): coming back to a tab must restore its scroll position, not jump to the caret.
        snapshotFlow { document.caret }.drop(1).collect { caret ->
            // A search or "go to line" may land inside a folded region: unfold it, like IntelliJ.
            if (document.folding.isHidden(caret.line)) document.folding.reveal(caret.line)
            scrollToRow(viewport, metrics, document.folding.rowOf(caret.line))
            val x = visualColumn(document.lines[caret.line], caret.column) * metrics.charWidth + metrics.paddingPx
            scrollToX(tab.horizontalScroll, metrics, x, viewportWidth() - metrics.gutterPx)
        }
    }
}

private suspend fun scrollToRow(viewport: EditorViewport, metrics: EditorMetrics, row: Int) {
    val visible = viewport.visibleRows(metrics.lineHeightPx)
    val first = viewport.firstRow
    val last = first + visible - 1
    val target =
        when {
            row < first - visible || row > last + visible -> (row - visible / 2).coerceAtLeast(0)
            row < first -> row
            row > last -> row - visible + 1
            else -> null
        }
    target?.let { viewport.scrollToRow(it) }
}

private fun scrollToX(horizontal: HorizontalScroll, metrics: EditorMetrics, x: Float, textViewport: Float) {
    val charWidth = metrics.charWidth
    if (textViewport > 0) {
        when {
            x < horizontal.offset + metrics.paddingPx -> {
                horizontal.scrollTo(x - CARET_MARGIN_COLUMNS * charWidth)
            }

            x > horizontal.offset + textViewport - RIGHT_EDGE_COLUMNS * charWidth -> {
                horizontal.scrollTo(x - textViewport + RIGHT_SCROLL_COLUMNS * charWidth)
            }
        }
    }
}

/** Text shown when a tab cannot display its content. */
@Composable
fun EditorMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(IdeColors.Editor), contentAlignment = Alignment.Center) {
        BasicText(text, Modifier.testTag(Tags.EDITOR_MESSAGE), style = TextStyle(color = IdeColors.TextSecondary))
    }
}
