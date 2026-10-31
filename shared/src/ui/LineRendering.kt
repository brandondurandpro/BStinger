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
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter

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
    val highlighted = SyntaxHighlighter.highlight(displayText(source.text), source.language, source.startsInComment)
    val placeholder = source.placeholder ?: return highlighted
    return buildAnnotatedString {
        append(highlighted)
        append(" ")
        withStyle(
            SpanStyle(color = IdeColors.TextSecondary, background = IdeColors.FoldPlaceholder),
        ) { append(placeholder) }
    }
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
        val start = decorations.selectionStart
        drawRect(
            IdeColors.TextSelection,
            Offset(origin + start * charWidth, 0f),
            Size((decorations.selectionEnd - start) * charWidth, height),
        )
    }
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
