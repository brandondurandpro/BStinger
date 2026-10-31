package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import kotlin.math.roundToInt

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

private fun DrawScope.drawBlame(text: String, metrics: EditorMetrics) {
    val layout = metrics.blameMeasurer.measure(text, metrics.blameStyle, softWrap = false, maxLines = 1)
    drawText(layout, topLeft = Offset(0f, (size.height - layout.size.height) / 2))
}

@Composable
internal fun CodeLine(
    source: LineSource,
    decorations: LineDecorations,
    metrics: EditorMetrics,
    horizontal: HorizontalScroll,
    caretOn: State<Boolean>,
) {
    val background =
        if (decorations.isCaretLine &&
            !decorations.hasSelection
        ) {
            IdeColors.CurrentLine
        } else {
            Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.lineHeight)
            .background(background)
            .semantics(mergeDescendants = true) {}
            .testTag(Tags.line(source.index)),
    ) {
        Gutter(source, decorations, metrics)
        LineText(source, decorations, metrics, horizontal, caretOn)
    }
}

@Composable
private fun Gutter(source: LineSource, decorations: LineDecorations, metrics: EditorMetrics) {
    Box(Modifier.width(metrics.gutterWidth).fillMaxHeight()) {
        decorations.change?.let { kind ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(changeColor(kind))
                    .testTag(Tags.changeMarker(source.index, kind.name)),
            )
        }
        if (decorations.deleted) DeletionMarker(source.index)
        BasicText(
            text = (source.index + 1).toString(),
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp + FOLD_ZONE + metrics.blameWidth),
            style =
            metrics.codeStyle.copy(
                color = if (decorations.isCaretLine) IdeColors.LineNumberCurrent else IdeColors.LineNumber,
                textAlign = TextAlign.End,
            ),
            softWrap = false,
            maxLines = 1,
        )
        decorations.blame?.let { blame ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(metrics.blameWidth)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = FOLD_ZONE + 4.dp)
                    .clipToBounds()
                    .drawBehind { drawBlame(blame, metrics) }
                    .testTag(Tags.blame(source.index)),
            )
        }
        if (source.foldable) {
            Chevron(
                expanded = !source.folded,
                modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
                    .testTag(Tags.foldMarker(source.index)),
                color = IdeColors.LineNumber,
            )
        }
    }
}

@Composable
private fun DeletionMarker(index: Int) {
    Canvas(Modifier.size(DELETION_MARKER_SIZE).testTag(Tags.changeMarker(index, "Deleted"))) {
        drawDeletionMarker(size.width)
    }
}

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

@Composable
private fun RowScope.LineText(
    source: LineSource,
    decorations: LineDecorations,
    metrics: EditorMetrics,
    horizontal: HorizontalScroll,
    caretOn: State<Boolean>,
) {
    // Highlighting is computed only for composed (visible) lines and cached while the text is unchanged.
    val text =
        remember(source.text, source.language, source.startsInComment, source.placeholder) { highlighted(source) }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clipToBounds()
            .drawBehind { drawDecorations(decorations, TEXT_PADDING.toPx() - horizontal.offset, metrics.charWidth) }
            .drawWithContent {
                drawContent()
                if (decorations.isCaretLine && caretOn.value) {
                    val x = TEXT_PADDING.toPx() - horizontal.offset + decorations.caretColumn * metrics.charWidth
                    drawRect(IdeColors.Caret, Offset(x, 0f), Size(2.dp.toPx(), size.height))
                }
            },
    ) {
        BasicText(
            text = text,
            style = metrics.codeStyle,
            softWrap = false,
            maxLines = 1,
            modifier =
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(TEXT_PADDING.roundToPx() - horizontal.offset.roundToInt(), 0) },
        )
    }
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
