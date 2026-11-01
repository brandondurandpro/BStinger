package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.syntax.DeclarationKind

private const val MAX_PREVIEW = 300

/** Popup frame: dimmed backdrop (click closes) and panel anchored near the top, like IntelliJ's popups. */
@Composable
internal fun PopupFrame(tag: String, onClose: () -> Unit, width: Int = 640, content: @Composable () -> Unit) {
    // The backdrop is a sibling of the panel, not its parent, so a click inside the panel is never offered to
    // the backdrop to close it (and pointer consumption is not needed, which would break the children's taps).
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .pointerInput(onClose) { detectTapGestures { onClose() } },
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 90.dp)
                .width(width.dp)
                .background(IdeColors.Popup, RoundedCornerShape(8.dp))
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
                .testTag(tag)
                .padding(10.dp),
        ) {
            content()
        }
    }
}

/** Keys of popups: arrows move in the list, Enter validates, Escape closes; other keys go to the text field. */
internal fun Modifier.popupKeys(onEnter: () -> Unit, onClose: () -> Unit, onMove: (Int) -> Unit = {}): Modifier =
    onPreviewKeyEvent { event ->
        val action: (() -> Unit)? =
            when {
                event.type != KeyEventType.KeyDown -> {
                    null
                }

                event.key == Key.DirectionDown -> {
                    { onMove(1) }
                }

                event.key == Key.DirectionUp -> {
                    { onMove(-1) }
                }

                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    onEnter
                }

                event.key == Key.Escape -> {
                    onClose
                }

                else -> {
                    null
                }
            }
        action?.invoke()
        action != null
    }

/** Selected row of a result list, wrapping around. */
internal class ListCursor {
    var selected by mutableIntStateOf(0)

    fun move(delta: Int, size: Int) {
        if (size > 0) selected = (selected + delta).mod(size)
    }
}

@Composable
internal fun <T> ResultList(items: List<T>, selected: Int, row: @Composable (Int, T) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyShown = visible.firstOrNull()?.let { it.index until visible.last().index }
        if (fullyShown != null && selected !in fullyShown) {
            listState.scrollToItem(maxOf(0, selected - visible.size + 2))
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp), state = listState) {
        itemsIndexed(items) { index, item -> row(index, item) }
    }
}

/** Background of a result row, highlighted when selected. */
internal fun Modifier.resultRow(selected: Boolean): Modifier =
    background(if (selected) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))

@Composable
internal fun ToggleChip(text: String, on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .background(if (on) IdeColors.Accent.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Label(text, fontSize = 11.sp, color = if (on) IdeColors.Text else IdeColors.TextSecondary)
    }
}

@Composable
internal fun KindBadge(kind: DeclarationKind) {
    Box(Modifier.size(16.dp).background(kindColor(kind), CircleShape), contentAlignment = Alignment.Center) {
        BasicText(kind.badge, style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    }
}

private fun kindColor(kind: DeclarationKind) =
    when (kind) {
        DeclarationKind.Class -> IdeColors.KindClass
        DeclarationKind.Interface -> IdeColors.KindInterface
        DeclarationKind.Object -> IdeColors.KindObject
        DeclarationKind.Function -> IdeColors.KindFunction
        DeclarationKind.Property -> IdeColors.KindProperty
    }

/** [text] with the characters at [indices] (fuzzy match) emphasized. */
internal fun highlightIndices(text: String, indices: List<Int>): AnnotatedString =
    buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = IdeColors.Text), 0, text.length)
        indices.filter { it in text.indices }.forEach {
            addStyle(SpanStyle(color = IdeColors.Accent, fontWeight = FontWeight.Bold), it, it + 1)
        }
    }

/** A search result line (preview limited to [MAX_PREVIEW] chars) with the match highlighted. */
internal fun highlightRange(text: String, start: Int, length: Int): AnnotatedString =
    buildAnnotatedString {
        val preview = text.take(MAX_PREVIEW)
        append(preview)
        addStyle(SpanStyle(color = IdeColors.Code), 0, preview.length)
        val s = start.coerceIn(0, preview.length)
        val e = (start + length).coerceIn(s, preview.length)
        if (e > s) addStyle(SpanStyle(background = IdeColors.SearchMatch, color = IdeColors.Text), s, e)
    }
