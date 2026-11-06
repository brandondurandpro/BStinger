package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.state.LineInlays
import org.example.kotlineditor.state.TAB_SIZE
import org.example.kotlineditor.state.visualColumn
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import org.example.kotlineditor.syntax.treesitter.LineSpans

/** Everything painted over the text of one line, in visual columns (`-1` = none). */
@Immutable
class LineDecorations(
    val caretColumn: Int = -1,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val matches: IntArray? = null,
    val matchLength: Int = 0,
    val currentMatch: Int = -1,
    val brackets: IntArray? = null,
    val usages: IntArray? = null,
    val usageLength: Int = 0,
    val change: GitChangeKind? = null,
    val deleted: Boolean = false,
    val blame: String? = null,
    /** Start and end screen cells of the selections of the other carets on the line, by pairs; none when null. */
    val extraSelections: IntArray? = null,
) {
    val isCaretLine: Boolean get() = caretColumn >= 0
    val hasSelection: Boolean get() = selectionStart in 0 until selectionEnd
}

/** The source of one line: its text, language and highlighting context. */
@Immutable
class LineSource(
    val index: Int,
    val text: String,
    val language: Language,
    val startsInComment: Boolean,
    val foldable: Boolean,
    val folded: Boolean,
    val placeholder: String?,
    /** Tree-sitter colors of the line; `null` when the line lexer colors it. */
    val spans: LineSpans? = null,
    /** The inlay hints drawn between its characters, none when null. */
    val inlays: LineInlays? = null,
    /** The styles of the language server's semantic tokens, over the others; none when null. */
    val semantic: LineSemantic? = null,
    /** Brackets open where the line starts, which colors its brackets by depth; `-1`: brackets not colored. */
    val bracketDepth: Int = -1,
)

internal val DELETION_MARKER_SIZE = 7.dp

/** Triangle in the top-left corner of the line below deleted lines. */
internal fun DrawScope.drawDeletionMarker(side: Float) {
    val path =
        Path().apply {
            moveTo(0f, 0f)
            lineTo(side, 0f)
            lineTo(0f, side)
            close()
        }
    drawPath(path, IdeColors.GitDeleted)
}

internal fun changeColor(kind: GitChangeKind): Color =
    when (kind) {
        GitChangeKind.Added -> IdeColors.GitAdded
        GitChangeKind.Modified -> IdeColors.GitModified
        GitChangeKind.Deleted -> IdeColors.GitDeleted
    }

internal fun highlighted(source: LineSource): AnnotatedString {
    val text = displayText(source.text)
    val treeSitterSpans = source.spans
    // The lexer colors the displayed text; tree-sitter counts the characters of the line, which tabs widen.
    val columns = if (treeSitterSpans != null && '\t' in source.text) displayColumns(source.text) else null
    val spans = treeSitterSpans ?: SyntaxHighlighter.highlight(text, source.language, source.startsInComment)
    val highlighted =
        styled(text, spans, columns)
            .let { withSemantic(it, source.text, source.semantic) }
            .let { withBracketColors(it, spans, columns, source.bracketDepth) }
            .let { withInlays(it, source.text, source.inlays) }
    val placeholder = source.placeholder ?: return highlighted
    return buildAnnotatedString {
        append(highlighted)
        append(" ")
        withStyle(
            SpanStyle(color = IdeColors.TextSecondary, background = IdeColors.FoldPlaceholder),
        ) { append(placeholder) }
    }
}

// [text], the displayed [line], with its inlay hints inserted before their characters, greyed on their background.
private fun withInlays(text: AnnotatedString, line: String, inlays: LineInlays?): AnnotatedString {
    if (inlays == null || inlays.size == 0) return text
    return buildAnnotatedString {
        var from = 0
        for (index in 0 until inlays.size) {
            val at = visualColumn(line, inlays.columns[index]).coerceIn(from, text.length)
            append(text.subSequence(from, at))
            withStyle(INLAY_STYLE) { append(inlays.labels[index]) }
            from = at
        }
        append(text.subSequence(from, text.length))
    }
}

private val INLAY_STYLE = SpanStyle(color = IdeColors.InlayHint, background = IdeColors.InlayHintBackground)

// [text] with the colors of [spans]; [columns] maps their offsets to the columns of [text] (`null`: the same).
private fun styled(text: String, spans: LineSpans, columns: IntArray?): AnnotatedString {
    if (spans.size == 0) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    for (i in 0 until spans.size) {
        val start = columns?.get(spans.starts[i]) ?: spans.starts[i]
        val end = columns?.get(spans.ends[i]) ?: spans.ends[i]
        builder.addStyle(syntaxStyle(spans.styles[i]), start, end)
    }
    return builder.toAnnotatedString()
}

// Displayed column of each character offset of [line] (and of its end).
private fun displayColumns(line: String): IntArray {
    val columns = IntArray(line.length + 1)
    var column = 0
    line.forEachIndexed { index, c ->
        columns[index] = column
        column += if (c == '\t') TAB_SIZE else 1
    }
    columns[line.length] = column
    return columns
}

private fun displayText(line: String) =
    org.example.kotlineditor.state
        .displayLine(line)

/** Usages, search matches, brackets and selection of a line [height] tall, behind its text. */
internal fun DrawScope.drawDecorations(
    decorations: LineDecorations,
    origin: Float,
    charWidth: Float,
    height: Float = size.height,
) {
    drawColumns(decorations.usages, decorations.usageLength, origin, charWidth, height) { IdeColors.IdentifierUsage }
    drawColumns(decorations.matches, decorations.matchLength, origin, charWidth, height) {
        if (it == decorations.currentMatch) IdeColors.SearchMatchCurrent else IdeColors.SearchMatch
    }
    drawColumns(decorations.brackets, 1, origin, charWidth, height) { IdeColors.BracketMatch }
    if (decorations.hasSelection) {
        drawSelection(decorations.selectionStart, decorations.selectionEnd, origin, charWidth, height)
    }
    val extra = decorations.extraSelections ?: return
    for (i in extra.indices step 2) drawSelection(extra[i], extra[i + 1], origin, charWidth, height)
}

private fun DrawScope.drawSelection(start: Int, end: Int, origin: Float, charWidth: Float, height: Float) {
    drawRect(IdeColors.TextSelection, Offset(origin + start * charWidth, 0f), Size((end - start) * charWidth, height))
}

private inline fun DrawScope.drawColumns(
    columns: IntArray?,
    length: Int,
    origin: Float,
    charWidth: Float,
    height: Float,
    color: (Int) -> Color,
) {
    columns?.forEach { drawRect(color(it), Offset(origin + it * charWidth, 0f), Size(length * charWidth, height)) }
}
