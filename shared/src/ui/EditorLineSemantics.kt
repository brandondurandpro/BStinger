package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.displayLine
import org.example.kotlineditor.state.placeholder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the canvas draws, as semantics: an invisible node per visible line with its text, and its gutter marks, for
 * accessibility and UI tests. Nothing here is drawn, laid out as text, or takes pointer input (clicks reach the
 * editor's gesture handler). Only the lines entering the screen are composed, and not while the user scrolls
 * (wheel, drag, fling): the nodes then just follow the content, in the layout phase, and the set of lines catches up
 * once the scroll ends. Composing them on every frame of a scroll would cost far more than drawing the lines.
 */
@Composable
internal fun EditorLineSemantics(tab: EditorTab, document: Document, metrics: EditorMetrics, marks: GutterMarks) {
    val scroll = tab.verticalScroll
    val folding = document.folding
    val scope = rememberCoroutineScope()
    val first = rememberSettledFirstRow(scroll)
    val count = ceil(scroll.viewportHeightPx / metrics.lineHeightPx).toInt() + 1
    val last = minOf(folding.rowCount(), first + count)
    val lastLine = document.lines.lastIndex
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                scrollToIndex { row ->
                    scope.launch { scroll.scrollToRow(row) }
                    true
                }
            },
    ) {
        for (row in first until last) {
            val index = folding.lineAt(row)
            key(index) {
                // A deletion after the last line is marked on the last line.
                val deletedAtEnd = index == lastLine && document.lineCount in marks.deleted
                val kinds =
                    listOfNotNull(
                        marks.changed[index],
                        GitChangeKind.Deleted.takeIf { index in marks.deleted || deletedAtEnd },
                    )
                LineNode(row, index, tab, document, metrics, kinds, marks.blame?.containsKey(index) == true)
            }
        }
    }
}

@Composable
private fun LineNode(
    row: Int,
    index: Int,
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    changes: List<GitChangeKind>,
    annotated: Boolean,
) {
    val scroll = tab.verticalScroll
    val folding = document.folding
    val line = document.lines[index]
    val folded = folding.isFolded(index)
    val text = displayLine(line) + if (folded) " " + folding.placeholder(index, document.lines) else ""
    val density = LocalDensity.current
    val gutter = with(density) { metrics.gutterPx.toDp() }
    val textWidth = with(density) { (text.length * metrics.charWidth).toDp() }
    Box(
        Modifier
            .offset { IntOffset(0, rowTop(row, scroll, metrics)) }
            .fillMaxWidth()
            .height(metrics.lineHeight)
            .semantics(mergeDescendants = true) {}
            .testTag(Tags.line(index)),
    ) {
        for (kind in changes) {
            val marker =
                if (kind == GitChangeKind.Deleted) {
                    Modifier.size(DELETION_MARKER_SIZE)
                } else {
                    Modifier.size(3.dp, metrics.lineHeight)
                }
            Box(marker.testTag(Tags.changeMarker(index, kind.name)))
        }
        if (tab.blameVisible && annotated) {
            Box(
                Modifier
                    .offset(x = gutter - metrics.blameWidth)
                    .size(metrics.blameWidth, metrics.lineHeight)
                    .testTag(Tags.blame(index)),
            )
        }
        if (folding.isFoldable(index)) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = gutter - 2.dp - 16.dp)
                    .size(16.dp)
                    .testTag(Tags.foldMarker(index)),
            )
        }
        Box(
            Modifier
                .offset { IntOffset(textLeft(metrics, tab.horizontalScroll.offset), 0) }
                .size(textWidth, metrics.lineHeight)
                .semantics { this.text = AnnotatedString(text) }
                .testTag(Tags.lineText(index)),
        )
    }
}

// The first row on screen, left as it was while a scroll is in progress.
@Composable
internal fun rememberSettledFirstRow(scroll: VerticalScroll): Int {
    var settled by remember(scroll) { mutableIntStateOf(scroll.firstRow) }
    LaunchedEffect(scroll) {
        snapshotFlow { if (scroll.scrollableState.isScrollInProgress) null else scroll.firstRow }
            .filterNotNull()
            .collect { settled = it }
    }
    return settled
}

private fun rowTop(row: Int, scroll: VerticalScroll, metrics: EditorMetrics): Int =
    ((row - scroll.firstRow) * metrics.lineHeightPx - scroll.rowOffsetPx).roundToInt()

// Where the canvas draws the text: the text area starts on a whole pixel.
private fun Density.textLeft(metrics: EditorMetrics, horizontalOffset: Float): Int =
    (metrics.gutterPx.roundToInt() + TEXT_PADDING.roundToPx() - horizontalOffset).roundToInt()
