package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.inlaysOf
import org.example.kotlineditor.state.visualColumn

/** Popup mode of the inlay hints: the line of the caret, its hints included, in a popup above it. */
@Composable
internal fun CaretLineHints(
    insight: CodeInsightState,
    document: Document,
    tab: EditorTab,
    metrics: EditorMetrics,
    viewport: () -> IntSize,
) {
    val hintsShown = insight.inlayHintsMode == InlayHintsMode.Popup
    val caretLine = document.caret.line
    val lineHints =
        insight
            .insightOf(document)
            ?.inlayHints
            ?.filter { it.line == caretLine }
            .orEmpty()
    if (hintsShown && lineHints.isNotEmpty()) {
        val text = document.lines.getOrElse(caretLine) { "" }
        val indent = text.length - text.trimStart().length
        Anchored(document, TextPos(caretLine, indent), tab, metrics, viewport, Placement.Above) {
            InlayHintsView(document, caretLine, indent, lineHints)
        }
    }
}

// Line [index] in its colors, without its indentation, its hints inserted before their characters.
@Composable
private fun InlayHintsView(document: Document, index: Int, indent: Int, hints: List<InlayHint>) {
    val text = document.lines[index]
    val source =
        LineSource(
            index,
            text,
            document.language,
            document.commentStates?.getOrNull(index) == true,
            foldable = false,
            folded = false,
            placeholder = null,
            spans = document.syntax?.highlights?.spansFor(index, text),
            inlays = inlaysOf(hints)[index],
        )
    val line = highlighted(source)
    val shown = line.subSequence(visualColumn(text, indent).coerceAtMost(line.length), line.length)
    BasicText(
        shown,
        Modifier
            .popupFrame()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(EditorTags.INLAY_HINTS_POPUP),
        style = LocalCodeStyle.current.copy(color = IdeColors.Code),
        maxLines = 1,
    )
}
