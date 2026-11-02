package org.example.kotlineditor.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.DEFAULT_EDITOR_FONT_SIZE
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.columnForVisual
import org.example.kotlineditor.state.selectWordAt
import org.example.kotlineditor.state.toggle
import kotlin.math.floor
import kotlin.math.roundToInt

internal val TEXT_PADDING = 6.dp

/** Gutter area, right of the line numbers, holding the fold markers. */
internal val FOLD_ZONE = 14.dp

private const val LINE_HEIGHT_PER_POINT = 20f / DEFAULT_EDITOR_FONT_SIZE
private const val MEASURED_CHARS = 100
private const val MIN_LINE_NUMBER_DIGITS = 3
private const val SLACK_COLUMNS = 8
private const val MULTI_CLICK_MILLIS = 400L
private val GUTTER_PADDING = 28.dp
private val BLAME_WIDTH = 170.dp
private val BLAME_FONT_SIZE = 10.sp

/** Sizes of an editor for a font size: everything is a multiple of the (monospace) character width. */
@Immutable
internal class EditorMetrics(
    val codeStyle: TextStyle,
    val lineHeight: Dp,
    val lineHeightPx: Float,
    val charWidth: Float,
    val paddingPx: Float,
    val gutterWidth: Dp,
    val gutterPx: Float,
    val foldZonePx: Float,
    val blameWidth: Dp,
    val blameStyle: TextStyle,
) {
    /** Horizontal scroll range: widest line plus some slack, minus what fits on screen. */
    fun maxHorizontalOffset(maxLineLength: Int, viewportWidth: Float): Float =
        maxOf(0f, (maxLineLength + SLACK_COLUMNS) * charWidth + paddingPx - (viewportWidth - gutterPx))

    fun isOnFoldMarker(x: Float): Boolean = x in gutterPx - foldZonePx..gutterPx

    /** Document position under [position] (in the editor's coordinates). */
    fun hit(position: Offset, viewport: VerticalScroll, horizontal: HorizontalScroll, document: Document): TextPos {
        val folding = document.folding
        val rowOffset = floor((position.y + viewport.rowOffsetPx) / lineHeightPx).toInt()
        val line = folding.lineAt((viewport.firstRow + rowOffset).coerceIn(0, folding.rowCount() - 1))
        val visual =
            ((position.x - gutterPx - paddingPx + horizontal.offset) / charWidth)
                .roundToInt()
                .coerceAtLeast(0)
        return TextPos(line, columnForVisual(document.lines[line], visual))
    }
}

@Composable
internal fun rememberEditorMetrics(lineCount: Int, fontSize: Float, blameVisible: Boolean = false): EditorMetrics {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalCodeStyle.current
    val digits = maxOf(MIN_LINE_NUMBER_DIGITS, lineCount.toString().length)
    return remember(density, baseStyle, fontSize, digits, blameVisible) {
        val lineHeightUnits = (fontSize * LINE_HEIGHT_PER_POINT).roundToInt()
        val codeStyle = baseStyle.copy(fontSize = fontSize.sp, lineHeight = lineHeightUnits.sp)
        val charWidth = measurer.measure("0".repeat(MEASURED_CHARS), codeStyle).size.width / MEASURED_CHARS.toFloat()
        val blameWidth = if (blameVisible) BLAME_WIDTH else 0.dp
        with(density) {
            val gutterWidth = (charWidth * digits).toDp() + GUTTER_PADDING + FOLD_ZONE + blameWidth
            EditorMetrics(
                codeStyle = codeStyle,
                lineHeight = lineHeightUnits.dp,
                lineHeightPx = lineHeightUnits.dp.toPx(),
                charWidth = charWidth,
                paddingPx = TEXT_PADDING.toPx(),
                gutterWidth = gutterWidth,
                gutterPx = gutterWidth.toPx(),
                foldZonePx = FOLD_ZONE.toPx(),
                blameWidth = blameWidth,
                blameStyle = codeStyle.copy(color = IdeColors.TextSecondary, fontSize = BLAME_FONT_SIZE),
            )
        }
    }
}

/** Counts successive clicks on the same line: 1 places the caret, 2 selects a word, 3 the line. */
internal class ClickCounter {
    private var lastTime = 0L
    private var lastLine = -1
    private var count = 0

    fun register(time: Long, line: Int): Int {
        count = if (time - lastTime < MULTI_CLICK_MILLIS && line == lastLine) count + 1 else 1
        lastTime = time
        lastLine = line
        return count
    }
}

/** What a mouse gesture in the editor acts on. */
internal class GestureTarget(
    val document: Document,
    val tab: EditorTab,
    val viewport: VerticalScroll,
    val metrics: EditorMetrics,
    val clicks: ClickCounter,
    val focus: FocusRequester,
    val onCtrlClick: (TextPos) -> Unit,
    val autoScroll: (Float) -> Unit,
)

/** One mouse gesture: fold marker click, Ctrl+click (go to declaration), or click(s) and drag selection. */
internal suspend fun AwaitPointerEventScope.editorGesture(target: GestureTarget) {
    val down = awaitFirstDown()
    target.focus.requestFocus()
    val pos = target.metrics.hit(down.position, target.viewport, target.tab.horizontalScroll, target.document)
    val modifiers = currentEvent.keyboardModifiers
    when {
        target.metrics.isOnFoldMarker(down.position.x) && target.document.folding.isFoldable(pos.line) -> {
            target.document.folding.toggle(pos.line)
        }

        modifiers.isCtrlPressed || modifiers.isMetaPressed -> {
            target.onCtrlClick(pos)
        }

        else -> {
            click(target, down, pos, modifiers.isShiftPressed)
        }
    }
}

private suspend fun AwaitPointerEventScope.click(
    target: GestureTarget,
    down: PointerInputChange,
    pos: TextPos,
    shift: Boolean,
) {
    val document = target.document
    when (target.clicks.register(down.uptimeMillis, pos.line)) {
        1 -> {
            document.moveTo(pos, extendSelection = shift)
            drag(target)
        }

        2 -> {
            document.selectWordAt(pos)
        }

        else -> {
            document.select(TextPos(pos.line, 0), TextPos(pos.line + 1, 0))
        }
    }
}

// Extends the selection while the button is held, scrolling when the mouse leaves the editor vertically.
private suspend fun AwaitPointerEventScope.drag(target: GestureTarget) {
    var change = awaitPointerEvent().changes.firstOrNull()
    while (change != null && change.pressed) {
        val p = change.position
        val pos = target.metrics.hit(p, target.viewport, target.tab.horizontalScroll, target.document)
        target.document.moveTo(pos, extendSelection = true)
        when {
            p.y < 0 -> target.autoScroll(-target.metrics.lineHeightPx)
            p.y > size.height -> target.autoScroll(target.metrics.lineHeightPx)
            else -> Unit
        }
        change.consume()
        change = awaitPointerEvent().changes.firstOrNull()
    }
}
