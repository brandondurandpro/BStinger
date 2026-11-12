package org.example.kotlineditor.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.CompletionState
import org.example.kotlineditor.state.DEFAULT_EDITOR_FONT_SIZE
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.state.LineInlays
import org.example.kotlineditor.state.ScreenLine
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.closesTags
import org.example.kotlineditor.state.extendSelection
import org.example.kotlineditor.state.identifierAt
import org.example.kotlineditor.state.matchingBracket
import org.example.kotlineditor.state.reveal
import org.example.kotlineditor.state.visualColumn
import org.example.kotlineditor.state.visualColumnOf
import org.example.kotlineditor.state.wordOccurrences
import kotlin.math.roundToInt

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
    // A click on a blame annotation: the commit that last changed the line.
    onBlameClick: (BlameLine) -> Unit = {},
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
        val onBlame: (Int) -> Unit = { line -> tab.blame?.firstOrNull { it.line == line }?.let(onBlameClick) }
        val autoScroll: (Float) -> Unit = { delta -> scope.launch { viewport.scrollBy(delta) } }
        val gestures = EditorGestures(clicks, focusRequester, onCtrlClick, onBlame, autoScroll)
        EditorTextArea(TextAreaContent(tab, document, metrics, highlights, hints, caretOn), insight, path, gestures)
        EditorScrollbars(tab, metrics) { viewportWidth }
        if (completion != null) FollowCompletion(completion, tab, document, metrics) { viewportHeight }
        if (insight != null && path != null) {
            InsightLayer(
                insight,
                path,
                tab,
                document,
                metrics,
                focusRequester,
            ) { IntSize(viewportWidth.roundToInt(), viewportHeight.roundToInt()) }
        }
    }
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
        val closeTags = path != null && closesTags(path)
        val completionShown = completion?.popup?.document === document
        insight?.let { ctrlKey(event, it) }
        val byInsight = insight != null && !completionShown
        val smartPath = path?.takeIf { byInsight && isExtendSelection(event) }
        if (smartPath != null && insight != null) {
            insight.extendSelection(smartPath, document)
            true
        } else if (byInsight && insightKey(event, insight, document)) {
            true
        } else if (completion != null && path != null) {
            completionKey(event, completion, path, document) { handleEditorKey(event, document, page(), closeTags) }
        } else {
            handleEditorKey(event, document, page(), closeTags)
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
    val inlays = document.inlays[index]
    val cells = LineCells(line, inlays)
    val selection = document.selectionRange()?.takeIf { index in it.first.line..it.second.line }
    val word = hints.identifier.takeUnless { serverHighlights }
    return LineDecorations(
        caretColumn = if (caret.line == index) cells.cellOf(caret.column) else -1,
        selectionStart = selection?.let { cells.selectionStart(index, it.first) } ?: -1,
        selectionEnd = selection?.let { cells.selectionEnd(index, it.second) } ?: -1,
        matches = highlights?.byLine?.get(index),
        matchLength = highlights?.length ?: 0,
        currentMatch = highlights?.current?.takeIf { it.line == index }?.let { cells.cellOf(it.column) } ?: -1,
        brackets = cells.ofBrackets(hints, index),
        usages = word?.let { cells.ofAll(wordOccurrences(line, it)) },
        usageLength = word?.length ?: 0,
        change = change,
        deleted = deleted,
        blame = blame,
        extraSelections = document.extraCarets.extraSelectionsOn(index, line, inlays),
    )
}

/**
 * The cells of the columns of one line. What they depend on (tabs, hints) is looked for once: counted from the
 * start of the line for each of the thousands of occurrences of a name on a minified line, in each frame, they
 * froze the scroll.
 */
private class LineCells(private val line: String, private val inlays: LineInlays?) {
    private val screenLine = ScreenLine(line, inlays)

    // A caret keeps a column beyond the end of a shorter line.
    fun cellOf(column: Int): Int {
        val onLine = column <= line.length
        return if (onLine) screenLine.startOf(column) else visualColumn(line, column, inlays)
    }

    fun ofAll(columns: IntArray?): IntArray? = columns?.let { all -> IntArray(all.size) { cellOf(all[it]) } }

    fun ofBrackets(hints: CaretHints, index: Int): IntArray? {
        val brackets = hints.brackets?.toList().orEmpty()
        val onLine = brackets.filter { it.line == index }
        if (onLine.isEmpty()) return null
        return IntArray(onLine.size) { cellOf(onLine[it].column) }
    }

    fun selectionStart(index: Int, start: TextPos): Int = if (index == start.line) cellOf(start.column) else 0

    // A selection that goes on covers the end of the line too.
    fun selectionEnd(index: Int, end: TextPos): Int = if (index == end.line) cellOf(end.column) else screenLine.end + 1
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
        val reveal: suspend (TextPos) -> Unit = { caret ->
            tab.revealCaret = false
            // A search or "go to line" may land inside a folded region: unfold it, like IntelliJ.
            if (document.folding.isHidden(caret.line)) document.folding.reveal(caret.line)
            scrollToRow(viewport, metrics, document.folding.rowOf(caret.line))
            val x = document.visualColumnOf(caret.line, caret.column) * metrics.charWidth + metrics.paddingPx
            scrollToX(tab.horizontalScroll, metrics, x, viewportWidth() - metrics.gutterPx)
        }
        // A file opened by a jump shows where it landed, once the editor knows how tall it is.
        if (tab.revealCaret) {
            snapshotFlow { viewport.viewportHeightPx }.first { it > 0f }
            reveal(document.caret)
        }
        // drop(1): coming back to a tab must restore its scroll position, not jump to the caret.
        snapshotFlow { document.caret }.drop(1).collect(reveal)
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
        BasicText(
            text,
            Modifier.testTag(EditorTags.EDITOR_MESSAGE),
            style = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.TextSecondary),
        )
    }
}
