package org.example.kotlineditor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.HighlightKind
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.DocumentInsight
import org.example.kotlineditor.state.LinkTarget
import org.example.kotlineditor.state.ScreenLine

private val WAVE_HEIGHT = 2.dp
private val WAVE_LENGTH = 4.dp
private val UNDERLINE = 1.dp
private const val HINT_GAP_COLUMNS = 2
private const val DIMMED_ALPHA = 0.45f

/** One problem's part on one line, in characters of the line (`end` past the text: to the end of the line). */
@Immutable
internal class LineProblem(
    val start: Int,
    val end: Int,
    val severity: DiagnosticSeverity,
    val unnecessary: Boolean,
    val deprecated: Boolean,
)

/** One occurrence of the symbol at the caret on a line, in characters. */
@Immutable
internal class LineHighlight(val start: Int, val end: Int, val kind: HighlightKind)

/**
 * What the language server says about the lines, indexed once per change rather than per drawn line: problems,
 * usage hints, occurrences of the symbol at the caret, and the link under the mouse with Ctrl held.
 */
@Immutable
internal class InsightMarks(insight: DocumentInsight?, val link: LinkTarget?, document: Document?) {
    val problems: Map<Int, List<LineProblem>> =
        if (document == null) emptyMap() else problemsByLine(insight?.diagnostics.orEmpty(), document)
    val hints: Map<Int, String> =
        insight
            ?.usageCounts
            .orEmpty()
            .groupBy { it.range.startLine }
            .mapValues { (_, counts) -> usageText(counts.first().count) }
    val highlights: Map<Int, List<LineHighlight>> =
        insight
            ?.highlights
            .orEmpty()
            .filter { it.range.startLine == it.range.endLine }
            .groupBy({ it.range.startLine }) { LineHighlight(it.range.startColumn, it.range.endColumn, it.kind) }

    val semantic: Map<Int, LineSemantic> = semanticByLine(insight?.semanticTokens.orEmpty())

    /** Whether the language server's occurrences replace the word-based ones. */
    val hasHighlights: Boolean get() = highlights.isNotEmpty()

    companion object {
        /** No language server for the document. */
        val None = InsightMarks(null, null, null)
    }
}

/** "no usages", "1 usage", "3 usages". */
fun usageText(count: Int): String =
    when (count) {
        0 -> "no usages"
        1 -> "1 usage"
        else -> "$count usages"
    }

private fun problemsByLine(diagnostics: List<Diagnostic>, document: Document): Map<Int, List<LineProblem>> {
    val byLine = HashMap<Int, MutableList<LineProblem>>()
    for (diagnostic in diagnostics) {
        val range = diagnostic.range
        val lastLine = range.endLine.coerceAtMost(document.lines.lastIndex)
        for (line in range.startLine..lastLine) {
            val length = document.lines[line].length
            byLine.getOrPut(line) { ArrayList() } += lineProblem(diagnostic, line, length)
        }
    }
    // The most serious drawn last, on top.
    return byLine.mapValues { (_, problems) -> problems.sortedByDescending { it.severity.ordinal } }
}

// The part of [diagnostic] on [line], of [length] characters.
private fun lineProblem(diagnostic: Diagnostic, line: Int, length: Int): LineProblem {
    val range = diagnostic.range
    val start = if (line == range.startLine) range.startColumn else 0
    val end = if (line == range.endLine) range.endColumn else length
    // An empty range is marked on the character after it, or before it at the end of the line.
    val widened = if (end > start) end else start + 1
    val shift = if (start >= length && start > 0) 1 else 0
    return LineProblem(
        start - shift,
        widened - shift,
        diagnostic.severity,
        diagnostic.unnecessary,
        diagnostic.deprecated,
    )
}

internal fun problemColor(severity: DiagnosticSeverity): Color =
    when (severity) {
        DiagnosticSeverity.Error -> IdeColors.ProblemError
        DiagnosticSeverity.Warning -> IdeColors.ProblemWarning
        DiagnosticSeverity.Information -> IdeColors.ProblemInformation
        DiagnosticSeverity.Hint -> IdeColors.ProblemInformation
    }

/** The language server's occurrences of the symbol at the caret, behind the text of [line]. */
internal fun DrawScope.drawSymbolHighlights(
    highlights: List<LineHighlight>?,
    line: ScreenLine,
    charWidth: Float,
    height: Float,
) {
    highlights?.forEach { highlight ->
        val start = line.startOf(highlight.start)
        val end = line.endOf(highlight.end)
        val color = if (highlight.kind == HighlightKind.Write) IdeColors.IdentifierWrite else IdeColors.IdentifierUsage
        drawRect(color, Offset(start * charWidth, 0f), Size((end - start) * charWidth, height))
    }
}

/** Problems of [line] over its text: wavy underlines, struck-through deprecated code, dimmed unused code. */
internal fun DrawScope.drawProblems(problems: List<LineProblem>?, line: ScreenLine, charWidth: Float, height: Float) {
    problems?.forEach { problem ->
        val start = line.startOf(problem.start)
        val endColumn = line.endOf(problem.end)
        // Past the end of the line (a missing semicolon): one cell after the text.
        val end = if (problem.end > line.text.length) endColumn + 1 else endColumn.coerceAtLeast(start + 1)
        val left = start * charWidth
        val right = end * charWidth
        when {
            problem.unnecessary -> {
                drawRect(
                    IdeColors.Editor.copy(alpha = DIMMED_ALPHA),
                    Offset(left, 0f),
                    Size(right - left, height),
                )
            }

            problem.deprecated -> {
                drawLine(
                    IdeColors.Code,
                    Offset(left, height / 2),
                    Offset(right, height / 2),
                    UNDERLINE.toPx(),
                )
            }

            problem.severity == DiagnosticSeverity.Hint -> {
                // a hint is not drawn, like in IntelliJ
            }

            problem.severity == DiagnosticSeverity.Information -> {
                drawLine(
                    problemColor(problem.severity),
                    Offset(left, height - 1.dp.toPx()),
                    Offset(right, height - 1.dp.toPx()),
                    UNDERLINE.toPx(),
                )
            }

            else -> {
                drawWave(left, right, height, problemColor(problem.severity))
            }
        }
    }
}

private fun DrawScope.drawWave(left: Float, right: Float, height: Float, color: Color) {
    val amplitude = WAVE_HEIGHT.toPx()
    val step = WAVE_LENGTH.toPx() / 2
    val bottom = height - 1.dp.toPx()
    val path = Path()
    path.moveTo(left, bottom)
    var x = left
    var up = true
    while (x < right) {
        x += step
        path.lineTo(x.coerceAtMost(right), if (up) bottom - amplitude else bottom)
        up = !up
    }
    drawPath(path, color, style = Stroke(width = UNDERLINE.toPx()))
}

/** The link under the mouse (Ctrl held): an underline in the link color. */
internal fun DrawScope.drawLink(link: LinkTarget?, index: Int, line: ScreenLine, charWidth: Float, height: Float) {
    if (link == null || link.line != index) return
    val start = line.startOf(link.start) * charWidth
    val end = line.endOf(link.end) * charWidth
    drawLine(IdeColors.Link, Offset(start, height - 2.dp.toPx()), Offset(end, height - 2.dp.toPx()), UNDERLINE.toPx())
}

/** Where the usage hint of a line starts, in visual columns: two cells after its text. */
internal fun hintColumn(line: ScreenLine): Int = line.end + HINT_GAP_COLUMNS
