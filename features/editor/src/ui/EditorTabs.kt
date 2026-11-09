package org.example.kotlineditor.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import org.example.kotlineditor.state.EditorPane
import org.example.kotlineditor.state.EditorState
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.closeOthers
import org.example.kotlineditor.state.closeTabsToLeft
import org.example.kotlineditor.state.closeTabsToRight
import org.example.kotlineditor.state.splitRight
import org.example.kotlineditor.state.unsplit

/** Editor tabs: active one underlined, close button on hover, middle click closes. */
@Composable
fun EditorTabs(editor: EditorState, pane: EditorPane, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val active = pane.activeTab
    val density = LocalDensity.current
    var contextMenu by remember { mutableStateOf<Pair<String, DpOffset>?>(null) }
    // Read by the effect only: changing it recomposes nothing.
    val firstPlacement = remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        val index = pane.tabs.indexOf(active)
        val first = firstPlacement.value
        firstPlacement.value = false
        // The first time (a reopened project), the strip is placed once laid out, without scrolling before the user.
        if (first && index >= 0) snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val hidden = index >= 0 && !isFullyVisible(listState, index)
        when {
            hidden && first -> listState.scrollToItem(index)
            hidden -> listState.animateScrollToItem(index)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(IdeColors.Panel)
            .wheelScrollsHorizontally(listState)
            .testTag(EditorTags.TABS),
    ) {
        // The wheel scrolls the tabs once they overflow, like the terminal's tabs.
        LazyRow(Modifier.fillMaxWidth().fillMaxHeight(), state = listState) {
            items(pane.tabs, key = { it.key }) { tab ->
                EditorTabView(
                    tab,
                    active = tab == active,
                    onSelect = { editor.activate(tab) },
                    onClose = { editor.close(tab) },
                    onContextMenu = { position ->
                        contextMenu = tab.key to
                            with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
                    },
                ) {
                    contextMenu?.takeIf { it.first == tab.key }?.let { (_, offset) ->
                        TabContextMenu(editor, pane, tab, offset) { contextMenu = null }
                    }
                }
            }
        }
        HorizontalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp),
        )
    }
}

private class TabTags(val tab: String, val close: String)

private fun tagsOf(tab: EditorTab): TabTags {
    val commit = tab.commit
    return when {
        commit != null -> {
            TabTags(
                EditorTags.commitDiffTab(commit.hash, tab.relativePath),
                EditorTags.commitDiffTabClose(commit.hash, tab.relativePath),
            )
        }

        tab.isCompare -> {
            TabTags(EditorTags.compareTab(tab.relativePath), EditorTags.compareTabClose(tab.relativePath))
        }

        tab.isDiff -> {
            TabTags(EditorTags.diffTab(tab.relativePath), EditorTags.diffTabClose(tab.relativePath))
        }

        else -> {
            TabTags(EditorTags.tab(tab.relativePath), EditorTags.tabClose(tab.relativePath))
        }
    }
}

/** One tab of a file or a diff; the agent mode strip shows it among the conversations. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditorTabView(
    tab: EditorTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val modified = tab.document?.isModified == true
    val tags = tagsOf(tab)
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            onTabPress(event, onSelect, onClose, onContextMenu)
        }
    // The menu is a sibling of the tab, anchored on the whole tab rather than at the end of its content.
    Box(Modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (hovered && !active) IdeColors.Hover else Color.Transparent)
                .activeUnderline(active)
                .hoverable(interaction)
                .then(press)
                .testTag(tags.tab)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(tab.entry.name, isDirectory = false)
            Label(
                tab.title,
                Modifier.padding(start = 6.dp, end = 4.dp),
                color = if (modified) IdeColors.Modified else IdeColors.Text,
            )
            ModifiedDot(tab, modified)
            CloseTabButton(hovered || active, tags.close, onClose)
            Spacer(Modifier.width(2.dp))
        }
        menu()
    }
}

@Composable
private fun ModifiedDot(tab: EditorTab, modified: Boolean) {
    if (!modified) return
    Box(
        Modifier
            .size(6.dp)
            .background(IdeColors.Modified, CircleShape)
            .testTag(EditorTags.tabModified(tab.relativePath)),
    )
}

@Composable
private fun CloseTabButton(visible: Boolean, closeTag: String, onClose: () -> Unit) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (visible) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag(closeTag),
            ) { CloseIcon() }
        }
    }
}

// Context menu of a tab: close it, the others, both sides, everything, split or unsplit, or copy its path.
@Composable
private fun TabContextMenu(
    editor: EditorState,
    pane: EditorPane,
    tab: EditorTab,
    offset: DpOffset,
    onDismiss: () -> Unit,
) {
    val index = pane.tabs.indexOf(tab)
    val actions =
        CloseTabsActions(
            close = { editor.close(tab) },
            closeOthers = { editor.closeOthers(tab) },
            closeToLeft = { editor.closeTabsToLeft(tab) },
            closeToRight = { editor.closeTabsToRight(tab) },
            closeAll = { editor.closeAll() },
        )
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    CloseTabsMenu(index, pane.tabs.size, actions, offset, onDismiss, Modifier.testTag(EditorTags.TAB_MENU)) {
        MenuSeparator()
        TabMenuItem("Split Right", EditorTags.MENU_SPLIT_RIGHT, enabled = !editor.isSplit) {
            action {
                editor.activate(tab)
                editor.splitRight()
            }
        }
        TabMenuItem("Unsplit", EditorTags.MENU_UNSPLIT, enabled = editor.isSplit) {
            action {
                editor.activate(tab)
                editor.unsplit()
            }
        }
        MenuSeparator()
        TabMenuItem("Copy Path", EditorTags.MENU_COPY_TAB_PATH) { action { TextClipboard.set(tab.entry.path) } }
    }
}

fun Modifier.activeUnderline(active: Boolean): Modifier =
    drawBehind {
        if (active) drawRect(IdeColors.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
    }
