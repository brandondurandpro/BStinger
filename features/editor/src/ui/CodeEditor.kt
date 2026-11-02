package org.example.kotlineditor.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import org.example.kotlineditor.state.CodeInsightState
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import org.example.kotlineditor.state.CompletionState
import org.example.kotlineditor.state.DEFAULT_EDITOR_FONT_SIZE
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.identifierAt
import org.example.kotlineditor.state.matchingBracket
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
    fontSize: Float = DEFAULT_EDITOR_FONT_SIZE,
    onZoom: (delta: Float) -> Unit = {},
    onCtrlClick: (TextPos) -> Unit = {},
    // Code completion, and the path of the file it asks the language server about; null when the file has none.
    completion: CompletionState? = null,
    path: String? = null,
    // Hover, navigation, problems, usages: null when the file has no language server.
    insight: CodeInsightState? = null,
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
    // The syntax tree of a document is only kept while an editor shows it.
    DisposableEffect(document) {
        val syntax = document.syntax
        syntax?.show()
        onDispose { syntax?.hide() }
    }
    val scope = rememberCoroutineScope()
    val clicks = remember { ClickCounter() }
    val viewport = tab.verticalScroll
    val rows = document.folding.rowCount()
    SideEffect { viewport.updateLayout(rows, viewportHeight, metrics.lineHeightPx) }
    tab.horizontalScroll.updateRange(metrics.maxHorizontalOffset(document.maxLineLength, viewportWidth))
    FollowCaret(tab, viewport, document, metrics) { viewportWidth }
    val onSize: (IntSize) -> Unit = {
        viewportWidth = it.width.toFloat()
        viewportHeight = it.height.toFloat()
    }
    Box(
        modifier
            .background(IdeColors.Editor)
            .testTag(EditorTags.EDITOR)
            .onSizeChanged(onSize)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent(editorKeys(document, completion, path, insight) { viewport.visibleRows(metrics.lineHeightPx) })
            .focusable()
            .scrollable(tab.horizontalScroll.scrollableState, Orientation.Horizontal)
            // reverseDirection: the wheel and drags give negative deltas going down, as for any vertical list.
            .scrollable(viewport.scrollableState, Orientation.Vertical, reverseDirection = true)
            .zoomOnCtrlWheel(onZoom),
    ) {
        // Text cursor and mouse selection only over the text: the scrollbars are siblings drawn on top, so pressing
        // them never reaches this box (siblings do not share pointer events) and never selects text.
        val documentInsight = insight?.insightOf(document)
        val link = insight?.link?.takeIf { it.document === document }
        val insightMarks =
            remember(documentInsight?.diagnostics, documentInsight?.usageCounts, documentInsight?.highlights, link) {
                if (documentInsight == null) InsightMarks.None else InsightMarks(documentInsight, link, document)
            }
        val onHint: (TextPos, Offset) -> Boolean = { pos, offset ->
            hintClicked(insight, path, document, insightMarks, metrics, tab, pos, offset)
        }
        Box(
            Modifier
                .fillMaxSize()
                .testTag(EditorTags.EDITOR_TEXT)
                .pointerHoverIcon(if (link != null) PointerIcon.Hand else PointerIcon.Text)
                .hoverInsight(insight, path, document, tab, metrics)
                .pointerInput(document) {
                    val target =
                        GestureTarget(document, tab, viewport, metrics, clicks, focusRequester, onCtrlClick, onHint) { delta ->
                            scope.launch { viewport.scrollBy(delta) }
                        }
                    awaitEachGesture { editorGesture(target) }
                },
        ) {
            EditorCanvas(tab, document, metrics, highlights, hints, caretOn, insightMarks)
        }
        EditorScrollbars(tab, metrics) { viewportWidth }
        if (completion != null) FollowCompletion(completion, tab, document, metrics) { viewportHeight }
        if (insight != null && path != null) {
            FollowInsight(insight, path, document)
            val viewportSize = { IntSize(viewportWidth.roundToInt(), viewportHeight.roundToInt()) }
            CodeInsightOverlays(insight, tab, document, metrics, focusRequester, viewportSize)
            documentInsight?.let { shown ->
                ProblemsWidget(shown, { insight.nextProblem(document) }, Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
            }
        }
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

// The mouse over the text: documentation after a pause, the word under it as a link with Ctrl held.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.hoverInsight(
    insight: CodeInsightState?,
    path: String?,
    document: Document,
    tab: EditorTab,
    metrics: EditorMetrics,
): Modifier {
    if (insight == null || path == null) return this
    return onPointerEvent(PointerEventType.Move) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        if (change.pressed) return@onPointerEvent
        val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
        val pos = metrics.cellAt(change.position, tab.verticalScroll, tab.horizontalScroll, document)
        insight.mouseAt(path, document, pos, ctrl)
    }.onPointerEvent(PointerEventType.Exit) { insight.mouseLeft() }
}

// A click on the "3 usages" hint of a line shows the usages of its declaration.
@Suppress("LongParameterList")
private fun hintClicked(
    insight: CodeInsightState?,
    path: String?,
    document: Document,
    marks: InsightMarks,
    metrics: EditorMetrics,
    tab: EditorTab,
    pos: TextPos,
    offset: Offset,
): Boolean {
    val hint = marks.hints[pos.line]
    if (insight == null || path == null || hint == null) return false
    val line = document.lines[pos.line]
    val textLeft = metrics.gutterPx + metrics.paddingPx - tab.horizontalScroll.offset
    val left = textLeft + hintColumn(line) * metrics.charWidth
    val right = left + hint.length * metrics.charWidth
    if (offset.x !in left..right) return false
    val declaration = insight.insightOf(document)?.usageCounts?.firstOrNull { it.range.startLine == pos.line } ?: return false
    insight.showUsages(path, document, TextPos(declaration.range.startLine, declaration.range.startColumn))
    return true
}

// Keys go through the IntelliJ keymap, and through the completion popup first when the file has one, then
// through the lists code intelligence shows.
private fun editorKeys(
    document: Document,
    completion: CompletionState?,
    path: String?,
    insight: CodeInsightState?,
    page: () -> Int,
): (KeyEvent) -> Boolean =
    { event ->
        val completionShown = completion?.popup?.document === document
        if (event.key == Key.CtrlLeft || event.key == Key.CtrlRight) {
            if (event.type == KeyEventType.KeyUp) insight?.ctrlReleased()
        }
        if (insight != null && !completionShown && insightKey(event, insight, document)) {
            true
        } else if (completion != null && path != null) {
            completionKey(event, completion, path, document) { handleEditorKey(event, document, page()) }
        } else {
            handleEditorKey(event, document, page())
        }
    }

// The keys of the lists of places and quick fixes while one is shown, and Escape for whatever is shown.
private fun insightKey(event: KeyEvent, insight: CodeInsightState, document: Document): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val list = insight.locations?.document === document || insight.codeActions?.document === document
    val plain = !event.isCtrlPressed && !event.isAltPressed
    return when {
        list && plain && event.key == Key.DirectionDown -> true.also { insight.moveSelection(1) }
        list && plain && event.key == Key.DirectionUp -> true.also { insight.moveSelection(-1) }
        list && plain && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
            if (insight.locations != null) insight.openLocation() else insight.applyCodeAction()
            true
        }

        event.key == Key.Escape -> insight.escape()
        else -> false
    }
}

// The popup follows the caret within the word, and closes when it leaves it or when the editor goes away.
@Composable
private fun FollowCompletion(
    completion: CompletionState,
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    viewportHeight: () -> Float,
) {
    val popup = completion.popup
    if (popup != null && popup.document === document) CompletionList(popup, completion, tab, metrics, viewportHeight)
    LaunchedEffect(document) {
        snapshotFlow { document.caret to document.version }.collect { completion.caretMoved(document) }
    }
    DisposableEffect(document) {
        onDispose { if (completion.popup?.document === document) completion.dismiss() }
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
    // The language server highlights the occurrences of the symbol at the caret: no word-based ones.
    serverHighlights: Boolean = false,
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
    val word = hints.identifier.takeUnless { serverHighlights }
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
    viewport: VerticalScroll,
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

private suspend fun scrollToRow(viewport: VerticalScroll, metrics: EditorMetrics, row: Int) {
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
        BasicText(text, Modifier.testTag(EditorTags.EDITOR_MESSAGE), style = TextStyle(color = IdeColors.TextSecondary))
    }
}
