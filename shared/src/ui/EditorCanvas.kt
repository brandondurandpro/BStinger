package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.model.GitLineChange
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.placeholder
import org.example.kotlineditor.state.visualColumn
import kotlin.math.ceil
import kotlin.math.roundToInt

private val CHANGE_BAR_WIDTH = 3.dp
private val CARET_WIDTH = 2.dp
private val CHEVRON_SIZE = 16.dp
private val CHEVRON_END_PADDING = 2.dp
private val LINE_NUMBER_END = 16.dp
private val BLAME_START = 8.dp
private val BLAME_END = 4.dp
private const val EXPANDED_CHEVRON_DEGREES = 90f

/** Git marks and blame of the lines, indexed once per change rather than per drawn line. */
private class GutterMarks(changes: List<GitLineChange>, blame: List<BlameLine>?) {
    val changed = changes.filter { it.kind != GitChangeKind.Deleted }.associate { it.line to it.kind }
    val deleted = changes.filter { it.kind == GitChangeKind.Deleted }.map { it.line }.toSet()
    val blame = blame?.associate { it.line to "${it.author} ${it.date}" }
}

/**
 * The rows of an editor drawn on one canvas, like IntelliJ's editor: scrolling and caret moves only redraw, nothing
 * is composed per line, and the highlighted text of each line is laid out once ([LineRenderCache]). Everything is
 * read in the draw phase. The caret has a layer of its own, so its blinking does not redraw the text.
 */
@Composable
internal fun EditorCanvas(
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    highlights: SearchHighlights?,
    hints: CaretHints,
    caretOn: State<Boolean>,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val cache = remember(measurer, metrics.codeStyle) { LineRenderCache(measurer, metrics.codeStyle) }
    val marks = remember(tab.lineChanges, tab.blame) { GutterMarks(tab.lineChanges, tab.blame) }
    Box(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind { drawRows(tab, document, metrics, highlights, hints, cache, marks) },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind { if (caretOn.value) drawCaret(tab, document, metrics) },
        )
        EditorLineSemantics(tab, document, metrics)
    }
}

private fun DrawScope.drawRows(
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    highlights: SearchHighlights?,
    hints: CaretHints,
    cache: LineRenderCache,
    marks: GutterMarks,
) {
    val scroll = tab.verticalScroll
    val folding = document.folding
    val lineHeight = metrics.lineHeightPx
    val first = scroll.firstRow
    val last = minOf(folding.rowCount(), first + ceil(size.height / lineHeight).toInt() + 1)
    for (row in first until last) {
        val index = folding.lineAt(row)
        translate(top = (row - first) * lineHeight - scroll.rowOffsetPx) {
            drawRow(index, document, metrics, highlights, hints, cache, marks, tab.horizontalScroll.offset)
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawRow(
    index: Int,
    document: Document,
    metrics: EditorMetrics,
    highlights: SearchHighlights?,
    hints: CaretHints,
    cache: LineRenderCache,
    marks: GutterMarks,
    horizontalOffset: Float,
) {
    val folding = document.folding
    val line = document.lines[index]
    val folded = folding.isFolded(index)
    val deletedAtEnd = index == document.lines.lastIndex && document.lineCount in marks.deleted
    val deleted = index in marks.deleted || deletedAtEnd
    val decorations =
        decorations(document, index, line, highlights, hints, marks.changed[index], deleted, marks.blame?.get(index))
    val lineHeight = metrics.lineHeightPx
    if (decorations.isCaretLine && !decorations.hasSelection) {
        drawRect(IdeColors.CurrentLine, Offset.Zero, Size(size.width, lineHeight))
    }
    drawGutter(index, decorations, folding.isFoldable(index), folded, metrics, cache)
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
    // Layout places the text area on a whole pixel: so does this, or glyphs would be antialiased differently.
    val textLeft = metrics.gutterPx.roundToInt().toFloat()
    clipRect(left = textLeft, top = 0f, right = size.width, bottom = lineHeight) {
        val origin = textLeft + TEXT_PADDING.toPx() - horizontalOffset
        translate(left = origin) { drawDecorations(decorations, 0f, metrics.charWidth, lineHeight) }
        val textX = textLeft + TEXT_PADDING.roundToPx() - horizontalOffset.roundToInt()
        drawText(cache.line(source), topLeft = Offset(textX, 0f))
    }
}

private fun DrawScope.drawGutter(
    index: Int,
    decorations: LineDecorations,
    foldable: Boolean,
    folded: Boolean,
    metrics: EditorMetrics,
    cache: LineRenderCache,
) {
    val lineHeight = metrics.lineHeightPx
    decorations.change?.let { drawRect(changeColor(it), Offset.Zero, Size(CHANGE_BAR_WIDTH.toPx(), lineHeight)) }
    if (decorations.deleted) drawDeletionMarker(DELETION_MARKER_SIZE.toPx())
    val number = cache.number(index + 1)
    val numberRight = metrics.gutterPx - (LINE_NUMBER_END + FOLD_ZONE + metrics.blameWidth).toPx()
    val numberColor = if (decorations.isCaretLine) IdeColors.LineNumberCurrent else IdeColors.LineNumber
    drawText(number, color = numberColor, topLeft = Offset(numberRight - number.size.width, 0f))
    decorations.blame?.let { drawBlameAnnotation(it, metrics) }
    if (foldable) {
        val side = CHEVRON_SIZE.toPx()
        val left = metrics.gutterPx - CHEVRON_END_PADDING.toPx() - side
        translate(left = left, top = (lineHeight - side) / 2) {
            val angle = if (folded) 0f else EXPANDED_CHEVRON_DEGREES
            rotate(angle, pivot = Offset(side / 2, side / 2)) { drawChevron(side, IdeColors.LineNumber) }
        }
    }
}

private fun DrawScope.drawBlameAnnotation(text: String, metrics: EditorMetrics) {
    val left = (metrics.gutterPx - metrics.blameWidth.toPx()).roundToInt() + BLAME_START.roundToPx().toFloat()
    val right = metrics.gutterPx - (FOLD_ZONE + BLAME_END).toPx()
    if (right <= left) return
    val layout = metrics.blameMeasurer.measure(text, metrics.blameStyle, softWrap = false, maxLines = 1)
    clipRect(left = left, top = 0f, right = right, bottom = metrics.lineHeightPx) {
        drawText(layout, topLeft = Offset(left, (metrics.lineHeightPx - layout.size.height) / 2))
    }
}

private fun DrawScope.drawCaret(tab: EditorTab, document: Document, metrics: EditorMetrics) {
    val caret = document.caret
    val folding = document.folding
    if (caret.line !in document.lines.indices || folding.isHidden(caret.line)) return
    val scroll = tab.verticalScroll
    val y = (folding.rowOf(caret.line) - scroll.firstRow) * metrics.lineHeightPx - scroll.rowOffsetPx
    if (y + metrics.lineHeightPx < 0 || y > size.height) return
    val column = visualColumn(document.lines[caret.line], caret.column)
    val textLeft = metrics.gutterPx.roundToInt().toFloat()
    val x = textLeft + TEXT_PADDING.toPx() - tab.horizontalScroll.offset + column * metrics.charWidth
    clipRect(left = textLeft) {
        drawRect(IdeColors.Caret, Offset(x, y), Size(CARET_WIDTH.toPx(), metrics.lineHeightPx))
    }
}

/** Right-pointing chevron in a [side]-pixel square at the origin (rotate it to point down). */
internal fun DrawScope.drawChevron(side: Float, color: Color) {
    val path =
        Path().apply {
            moveTo(side * 0.40f, side * 0.28f)
            lineTo(side * 0.62f, side * 0.50f)
            lineTo(side * 0.40f, side * 0.72f)
        }
    drawPath(path, color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
