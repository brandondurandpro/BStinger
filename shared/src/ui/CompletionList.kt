package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionKind
import org.example.kotlineditor.state.CompletionPopup
import org.example.kotlineditor.state.CompletionState
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.visualColumn
import kotlin.math.roundToInt

private const val COMPLETION_ROWS = 10
private val COMPLETION_ROW_HEIGHT = 22.dp
private val COMPLETION_WIDTH = 460.dp
private val COMPLETION_BADGE = 16.dp

/**
 * The completion popup, under the word being completed (above it when there is no room below), like IntelliJ's:
 * a badge for the kind, the label with the typed letters emphasized, and the signature or type on the right.
 * Placed in the layout phase, so that scrolling the editor moves it without recomposing.
 */
@Composable
internal fun CompletionList(
    popup: CompletionPopup,
    completion: CompletionState,
    tab: EditorTab,
    metrics: EditorMetrics,
    viewportHeight: () -> Float,
) {
    val items = popup.items
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val rowHeightPx = with(density) { COMPLETION_ROW_HEIGHT.toPx() }
    val height = rowHeightPx * minOf(items.size, COMPLETION_ROWS)
    val listState = rememberLazyListState()
    LaunchedEffect(popup.selected, items) {
        val first = listState.firstVisibleItemIndex
        when {
            popup.selected < first -> listState.scrollToItem(popup.selected)
            popup.selected >= first + COMPLETION_ROWS -> listState.scrollToItem(popup.selected - COMPLETION_ROWS + 1)
        }
    }
    val document = popup.document
    val place: Density.() -> IntOffset = {
        // Horizontally: the badge left of the word's first letter.
        val lineText = document.lines.getOrElse(popup.line) { "" }
        val column = visualColumn(lineText, popup.replaceFrom)
        val textLeft = metrics.gutterPx + metrics.paddingPx - tab.horizontalScroll.offset
        val wordLeft = textLeft + column * metrics.charWidth
        val badgeSpace = COMPLETION_BADGE.toPx() + 8.dp.toPx()
        val x = (wordLeft - badgeSpace).coerceAtLeast(0f)

        // Vertically: below the line, or above it when it does not fit below.
        val row = document.folding.rowOf(popup.line)
        val rowTop = row * metrics.lineHeightPx - tab.verticalScroll.offsetPx
        val below = rowTop + metrics.lineHeightPx
        val above = rowTop - height
        val overflowsBelow = below + height > viewportHeight()
        val fitsAbove = above >= 0
        val y = if (overflowsBelow && fitsAbove) above else below
        IntOffset(x.roundToInt(), y.roundToInt())
    }
    Box(
        Modifier
            .offset(place)
            .width(COMPLETION_WIDTH)
            .background(IdeColors.Popup, RoundedCornerShape(4.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(4.dp))
            .testTag(Tags.COMPLETION),
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = COMPLETION_ROW_HEIGHT * COMPLETION_ROWS), state = listState) {
            itemsIndexed(items, key = { index, item -> "$index:${item.label}" }) { index, item ->
                CompletionRow(item, popup.typed, index == popup.selected) { completion.accept(index = index) }
            }
        }
    }
}

@Composable
private fun CompletionRow(item: CompletionItem, typed: String, selected: Boolean, onAccept: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(COMPLETION_ROW_HEIGHT)
            .background(if (selected) IdeColors.Selection else Color.Transparent)
            .pointerInput(onAccept) { detectTapGestures(onDoubleTap = { onAccept() }, onTap = { onAccept() }) }
            .testTag(Tags.completionItem(item.label))
            .semantics { this.selected = selected }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Badge(item.kind)
        BasicText(
            emphasized(item.label, typed),
            Modifier.weight(1f),
            style = LocalCodeStyle.current.copy(fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.detail?.let {
            BasicText(
                it,
                // At most half the row, against the right edge: the types line up whatever the label's length.
                Modifier.widthIn(max = COMPLETION_WIDTH / 2),
                style = TextStyle(color = IdeColors.TextSecondary, textAlign = TextAlign.End, fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(kind: CompletionKind) {
    val (letter, color) = badgeOf(kind)
    Box(
        Modifier.size(COMPLETION_BADGE).background(color, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(letter, style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    }
}

private fun badgeOf(kind: CompletionKind): Pair<String, Color> =
    when (kind) {
        CompletionKind.Function -> "f" to IdeColors.KindFunction
        CompletionKind.Method -> "m" to IdeColors.KindFunction
        CompletionKind.Variable -> "v" to IdeColors.KindProperty
        CompletionKind.Property -> "p" to IdeColors.KindProperty
        CompletionKind.Class -> "c" to IdeColors.KindClass
        CompletionKind.Interface -> "i" to IdeColors.KindInterface
        CompletionKind.Constant -> "k" to IdeColors.KindObject
        CompletionKind.Keyword -> "w" to IdeColors.LineNumber
        CompletionKind.Module -> "n" to IdeColors.KindObject
        CompletionKind.Other -> "·" to IdeColors.LineNumber
    }

// The label, with the letters the typed text matched emphasized: its prefix, or the letters found in order.
private fun emphasized(label: String, typed: String) =
    buildAnnotatedString {
        append(label)
        addStyle(SpanStyle(color = IdeColors.Text), 0, label.length)
        val offset = if (typed.startsWith('$') && !label.startsWith('$')) 1 else 0
        var next = offset
        for ((index, c) in label.withIndex()) {
            if (next < typed.length && c.equals(typed[next], ignoreCase = true)) {
                addStyle(SpanStyle(color = IdeColors.Accent, fontWeight = FontWeight.Bold), index, index + 1)
                next++
            }
        }
    }
