package org.example.kotlineditor.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.TerminalAttributes
import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.model.line

internal val TerminalBackground = Color(0xFF1E1F22)
private val DefaultForeground = Color(0xFFBCBEC4)
private val SelectionColor = IdeColors.Selection.copy(alpha = 0.55f)
private val CursorColor = IdeColors.Accent.copy(alpha = 0.55f)
private val ScrollbarColor = Color(0x59A6A6A6)

/** Size of one cell in pixels; the grid is the canvas divided by it. */
internal class CellMetrics(val width: Int, val height: Int) {
    fun columns(size: IntSize): Int = if (width > 0) size.width / width else 0

    fun rows(size: IntSize): Int = if (height > 0) size.height / height else 0
}

internal fun DrawScope.drawTerminal(
    snapshot: TerminalSnapshot?,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    metrics: CellMetrics,
    rows: Int,
    selection: TerminalSelection,
) {
    drawRect(TerminalBackground)
    if (snapshot == null) return
    val lineCount = minOf(rows, snapshot.rows)
    for (row in 0 until lineCount) {
        val line = snapshot.line(row)
        val top = row * metrics.height.toFloat()
        drawBackgrounds(line, top, metrics.width.toFloat(), metrics.height.toFloat())
        drawText(textMeasurer.measure(screenRow(line), style), topLeft = Offset(0f, top))
    }
    drawSelection(selection, snapshot, metrics)
    if (snapshot.scrollOffset == 0L) drawCursor(snapshot, metrics)
    drawScrollbar(snapshot, rows, metrics.width)
}

private fun DrawScope.drawBackgrounds(line: List<TerminalCell>, top: Float, cellWidth: Float, cellHeight: Float) {
    var column = 0
    while (column < line.size) {
        val background = line[column].background
        if (background == TerminalCell.DEFAULT_COLOR) {
            column++
            continue
        }
        var end = column + 1
        while (end < line.size && line[end].background == background) end++
        drawRect(
            rgb(background),
            topLeft = Offset(column * cellWidth, top),
            size = Size((end - column) * cellWidth, cellHeight),
        )
        column = end
    }
}

private fun screenRow(line: List<TerminalCell>): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var runStart = 0
    var runStyle: SpanStyle? = null
    for (cell in line) {
        val style = spanStyle(cell)
        if (runStyle != null && style != runStyle) {
            builder.addStyle(runStyle, runStart, builder.length)
            runStart = builder.length
        }
        runStyle = style
        builder.append(cell.text.ifEmpty { " " })
    }
    if (runStyle != null) builder.addStyle(runStyle, runStart, builder.length)
    return builder.toAnnotatedString()
}

private fun spanStyle(cell: TerminalCell): SpanStyle {
    val attributes = cell.attributes
    var color = cell.foreground.takeIf { it != TerminalCell.DEFAULT_COLOR }?.let(::rgb) ?: DefaultForeground
    if (attributes and TerminalAttributes.INVISIBLE != 0) color = Color.Transparent
    if (attributes and TerminalAttributes.FAINT != 0) color = color.copy(alpha = 0.6f)
    val decorations =
        buildList {
            if (attributes and TerminalAttributes.UNDERLINE != 0) add(TextDecoration.Underline)
            if (attributes and TerminalAttributes.STRIKETHROUGH != 0) add(TextDecoration.LineThrough)
        }
    return SpanStyle(
        color = color,
        fontWeight = if (attributes and TerminalAttributes.BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (attributes and TerminalAttributes.ITALIC != 0) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

private fun rgb(value: Int): Color = Color(0xFF000000.toInt() or value)

private fun DrawScope.drawSelection(selection: TerminalSelection, snapshot: TerminalSnapshot, metrics: CellMetrics) {
    val anchor = selection.anchor ?: return
    val cursor = selection.cursor ?: return
    val (start, end) = ordered(anchor, cursor)
    for (row in start.row..end.row.coerceAtMost(snapshot.rows - 1)) {
        if (row < 0) continue
        val first = if (row == start.row) start.column else 0
        val last = if (row == end.row) end.column else snapshot.columns - 1
        drawRect(
            SelectionColor,
            topLeft = Offset(first * metrics.width.toFloat(), row * metrics.height.toFloat()),
            size = Size((last - first + 1) * metrics.width.toFloat(), metrics.height.toFloat()),
        )
    }
}

private fun DrawScope.drawCursor(snapshot: TerminalSnapshot, metrics: CellMetrics) {
    if (!snapshot.cursorVisible) return
    val left = snapshot.cursorColumn * metrics.width.toFloat()
    val top = snapshot.cursorRow * metrics.height.toFloat()
    drawRect(CursorColor, topLeft = Offset(left, top), size = Size(metrics.width.toFloat(), metrics.height.toFloat()))
}

private fun DrawScope.drawScrollbar(snapshot: TerminalSnapshot, rows: Int, cellWidth: Int) {
    val total = snapshot.totalRows
    if (rows <= 0 || total <= rows) return
    val width = 3.dp.toPx()
    val track = size.height
    val thumb = (track * rows / total).coerceAtLeast(12.dp.toPx())
    val maxScroll = (total - rows).coerceAtLeast(1)
    val fromBottom = snapshot.scrollOffset.toFloat() / maxScroll
    val top = (track - thumb) * (1f - fromBottom)
    drawRect(
        ScrollbarColor,
        topLeft = Offset(size.width - width - cellWidth / 2f, top),
        size = Size(width, thumb),
    )
}

/** Text of the selection, rows separated by newlines and trailing blanks trimmed. */
internal fun selectedText(snapshot: TerminalSnapshot, anchor: CellPos?, cursor: CellPos?): String {
    if (anchor == null || cursor == null) return ""
    val (start, end) = ordered(anchor, cursor)
    return (start.row..end.row.coerceAtMost(snapshot.rows - 1)).joinToString("\n") { row ->
        val line = snapshot.line(row)
        val first = if (row == start.row) start.column else 0
        val last = if (row == end.row) end.column else line.lastIndex
        line
            .subList(first.coerceIn(0, line.size), (last + 1).coerceIn(0, line.size))
            .joinToString("") { it.text.ifEmpty { " " } }
            .trimEnd()
    }
}
