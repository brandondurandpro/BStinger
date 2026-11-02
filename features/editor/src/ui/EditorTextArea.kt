package org.example.kotlineditor.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.TextPos

/** What the mouse does over the text, besides the code intelligence. */
internal class EditorGestures(
    val clicks: ClickCounter,
    val focus: FocusRequester,
    val onCtrlClick: (TextPos) -> Unit,
    val onBlameClick: (Int) -> Unit,
    val autoScroll: (Float) -> Unit,
)

/** What the text area draws. */
internal class TextAreaContent(
    val tab: EditorTab,
    val document: Document,
    val metrics: EditorMetrics,
    val highlights: SearchHighlights?,
    val hints: CaretHints,
    val caretOn: State<Boolean>,
)

// Text cursor and mouse selection only over the text: the scrollbars are siblings drawn on top, so pressing them
// never reaches this box (siblings do not share pointer events) and never selects text.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EditorTextArea(
    content: TextAreaContent,
    insight: CodeInsightState?,
    path: String?,
    gestures: EditorGestures,
) {
    val tab = content.tab
    val document = content.document
    val metrics = content.metrics
    val link = insight?.link?.takeIf { it.document === document }
    val insightMarks = rememberInsightMarks(insight, document, link)
    val hintTarget = HintTarget(insight, path, document, insightMarks)
    val onHint: (TextPos, Offset) -> Boolean = { pos, offset -> hintClicked(hintTarget, metrics, tab, pos, offset) }
    // The usage hints are links: the hand shows they can be clicked.
    val overLink = link != null || insight?.hintUnderMouse === document
    // The first event of a mouse coming over the editor is an Enter, already at a position.
    val hintHover: AwaitPointerEventScope.(PointerEvent) -> Unit = { event ->
        val position = event.changes.firstOrNull()?.position
        // Like a click: the line under the mouse, wherever on it (the hint is after the end of its text).
        val pos = position?.let { metrics.hit(it, tab.verticalScroll, tab.horizontalScroll, document) }
        val onHint = pos != null && hintAt(hintTarget, metrics, tab, pos, position) != null
        insight?.hintUnderMouse = document.takeIf { onHint }
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(EditorTags.EDITOR_TEXT)
            .pointerHoverIcon(if (overLink) PointerIcon.Hand else PointerIcon.Text)
            .onPointerEvent(PointerEventType.Enter, onEvent = hintHover)
            .onPointerEvent(PointerEventType.Move, onEvent = hintHover)
            .onPointerEvent(PointerEventType.Exit) { insight?.hintUnderMouse = null }
            .hoverInsight(insight, path, document, tab, metrics)
            .pointerInput(document, metrics) {
                val target =
                    GestureTarget(
                        document,
                        tab,
                        tab.verticalScroll,
                        metrics,
                        gestures.clicks,
                        gestures.focus,
                        gestures.onCtrlClick,
                        onHint,
                        gestures.onBlameClick,
                        gestures.autoScroll,
                    )
                awaitEachGesture { editorGesture(target) }
            },
    ) {
        EditorCanvas(tab, document, metrics, content.highlights, content.hints, content.caretOn, insightMarks)
    }
}
