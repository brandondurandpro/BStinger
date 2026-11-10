package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.HierarchyNode
import org.example.kotlineditor.state.HierarchyPopup
import org.example.kotlineditor.state.closeHierarchy
import org.example.kotlineditor.state.openHierarchyItem
import org.example.kotlineditor.state.switchHierarchySide
import org.example.kotlineditor.state.title
import org.example.kotlineditor.state.toggleHierarchyNode

private val PANEL_WIDTH = 640.dp
private val ROW_HEIGHT = 24.dp
private val INDENT = 16.dp
private const val VISIBLE_ROWS = 16
private const val SCRIM_ALPHA = 0.25f
private val TITLE_STYLE get() =
    TextStyle(
        fontFamily = IdeFonts.ui,
        color = IdeColors.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
private val NAME_STYLE get() = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.Text, fontSize = 13.sp)
private val PLACE_STYLE get() = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.TextSecondary, fontSize = 12.sp)

/**
 * The call or type hierarchy over [document]: the function or type at the caret, then its callers (or callees,
 * subtypes, supertypes) as a tree whose nodes load when expanded. A click on a row opens its place, on its arrow
 * expands or collapses it; a click beside the tree closes it.
 */
@Composable
internal fun BoxScope.HierarchyView(insight: CodeInsightState, document: Document) {
    val popup = insight.hierarchy?.takeIf { it.document === document } ?: return
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures { insight.closeHierarchy() } }
            .testTag(EditorTags.HIERARCHY_SCRIM),
    )
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 32.dp, end = 12.dp)
            .width(PANEL_WIDTH)
            .popupFrame()
            .swallowClicks()
            .testTag(EditorTags.HIERARCHY),
    ) {
        HierarchyHeader(insight, popup)
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        HierarchyRows(insight, popup)
    }
}

@Composable
private fun HierarchyHeader(insight: CodeInsightState, popup: HierarchyPopup) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(popup.title, Modifier.weight(1f).testTag(EditorTags.HIERARCHY_TITLE), style = TITLE_STYLE)
        BasicText(
            "Show ${otherSide(popup.direction)}",
            Modifier
                .background(IdeColors.Selection, ButtonShape)
                .pointerInput(Unit) { detectTapGestures { insight.switchHierarchySide() } }
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(EditorTags.HIERARCHY_SWITCH)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = PLACE_STYLE.copy(color = IdeColors.Text),
        )
    }
}

private val ButtonShape = IdeShapes.Medium

private fun otherSide(direction: HierarchyDirection): String =
    when (direction) {
        HierarchyDirection.Callers -> "callees"
        HierarchyDirection.Callees -> "callers"
        HierarchyDirection.Supertypes -> "subtypes"
        HierarchyDirection.Subtypes -> "supertypes"
    }

@Composable
private fun HierarchyRows(insight: CodeInsightState, popup: HierarchyPopup) {
    val rows = popup.rows
    val selected = popup.selected.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val first = listState.firstVisibleItemIndex
        if (selected < first || selected >= first + VISIBLE_ROWS) listState.scrollToItem(selected)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = ROW_HEIGHT * VISIBLE_ROWS), state = listState) {
        itemsIndexed(rows) { index, node ->
            HierarchyRow(insight, node, index, index == selected)
        }
    }
    val last = rows.lastOrNull()
    val emptyRoot = popup.root.children?.isEmpty() == true
    when {
        popup.root.loading || last?.loading == true -> Note("Loading…", EditorTags.HIERARCHY_LOADING)
        popup.root.failed -> Note("The language server is not ready", EditorTags.HIERARCHY_FAILED)
        emptyRoot -> Note("No ${popup.direction.name.lowercase()}", EditorTags.HIERARCHY_EMPTY)
    }
}

@Composable
private fun Note(text: String, tag: String) {
    BasicText(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag(tag), style = PLACE_STYLE)
}

@Composable
private fun HierarchyRow(insight: CodeInsightState, node: HierarchyNode, index: Int, selected: Boolean) {
    val item = node.item
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) IdeColors.Selection else IdeColors.Popup)
            .pointerInput(index) { detectTapGestures { insight.openHierarchyItem(index) } }
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(EditorTags.hierarchyRow(index))
            .padding(start = 6.dp + INDENT * node.depth, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .pointerInput(index) { detectTapGestures { insight.toggleHierarchyNode(index) } }
                .testTag(EditorTags.hierarchyToggle(index)),
            contentAlignment = Alignment.Center,
        ) { BasicText(arrowOf(node), style = PLACE_STYLE) }
        BasicText(item.name, style = NAME_STYLE, maxLines = 1)
        val detail = item.detail?.let { "  $it" }.orEmpty()
        val calls = if (item.calls > 1) "  ×${item.calls}" else ""
        val failed = if (node.failed) "  (not ready: expand again)" else ""
        BasicText(
            detail + calls + failed,
            Modifier.weight(1f),
            style = PLACE_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val place = "${insight.host.relativePath(item.location.path)}:${item.location.range.startLine + 1}"
        BasicText(place, Modifier.padding(start = 8.dp), style = PLACE_STYLE, maxLines = 1)
    }
}

// Expanded, collapsed, or nothing more below.
private fun arrowOf(node: HierarchyNode): String {
    val leaf = node.children?.isEmpty() == true
    return when {
        leaf -> "·"
        node.expanded -> "▾"
        else -> "▸"
    }
}
