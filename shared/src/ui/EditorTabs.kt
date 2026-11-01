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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.EditorPane
import org.example.kotlineditor.state.EditorState
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.closeOthers
import org.example.kotlineditor.state.closeTabsToLeft
import org.example.kotlineditor.state.closeTabsToRight
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.state.splitRight
import org.example.kotlineditor.state.unsplit

/** Editor tabs: active one underlined, close button on hover, middle click closes. */
@Composable
fun EditorTabs(editor: EditorState, pane: EditorPane, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val active = pane.activeTab
    val density = LocalDensity.current
    var contextMenu by remember { mutableStateOf<Pair<String, DpOffset>?>(null) }
    LaunchedEffect(active) {
        val index = pane.tabs.indexOf(active)
        if (index >= 0 && !isFullyVisible(listState, index)) listState.animateScrollToItem(index)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(IdeColors.Panel)
            .testTag(Tags.TABS),
    ) {
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

internal fun isFullyVisible(listState: LazyListState, index: Int): Boolean {
    val info = listState.layoutInfo
    return info.visibleItemsInfo.any {
        it.index == index &&
            it.offset >= 0 &&
            it.offset + it.size <= info.viewportEndOffset
    }
}

private class TabTags(val tab: String, val close: String)

private fun tagsOf(tab: EditorTab): TabTags =
    if (tab.isDiff) {
        TabTags(Tags.diffTab(tab.relativePath), Tags.diffTabClose(tab.relativePath))
    } else {
        TabTags(Tags.tab(tab.relativePath), Tags.tabClose(tab.relativePath))
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditorTabView(
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
            .testTag(Tags.tabModified(tab.relativePath)),
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

// Middle click closes the tab, like in IntelliJ and browsers; right click opens its context menu.
@OptIn(ExperimentalComposeUiApi::class)
internal fun onTabPress(
    event: PointerEvent,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onContextMenu: (Offset) -> Unit,
) {
    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
    when {
        event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed -> onContextMenu(position)
        event.button == PointerButton.Tertiary || event.buttons.isTertiaryPressed -> onClose()
        event.buttons.isPrimaryPressed -> onSelect()
        else -> Unit
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
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.testTag(Tags.TAB_MENU),
    ) {
        TabMenuItem("Close", Tags.MENU_CLOSE_TAB) { action { editor.close(tab) } }
        TabMenuItem(
            "Close Others",
            Tags.MENU_CLOSE_OTHER_TABS,
            enabled = pane.tabs.size > 1,
        ) { action { editor.closeOthers(tab) } }
        TabMenuItem(
            "Close All to the Left",
            Tags.MENU_CLOSE_TABS_LEFT,
            enabled = index > 0,
        ) { action { editor.closeTabsToLeft(tab) } }
        TabMenuItem(
            "Close All to the Right",
            Tags.MENU_CLOSE_TABS_RIGHT,
            enabled = index in 0 until pane.tabs.lastIndex,
        ) { action { editor.closeTabsToRight(tab) } }
        MenuSeparator()
        TabMenuItem("Close All", Tags.MENU_CLOSE_ALL_TABS) { action { editor.closeAll() } }
        MenuSeparator()
        TabMenuItem("Split Right", Tags.MENU_SPLIT_RIGHT, enabled = !editor.isSplit) {
            action {
                editor.activate(tab)
                editor.splitRight()
            }
        }
        TabMenuItem("Unsplit", Tags.MENU_UNSPLIT, enabled = editor.isSplit) {
            action {
                editor.activate(tab)
                editor.unsplit()
            }
        }
        MenuSeparator()
        TabMenuItem("Copy Path", Tags.MENU_COPY_TAB_PATH) { action { TextClipboard.set(tab.entry.path) } }
    }
}

@Composable
internal fun TabMenuItem(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 13.sp) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(30.dp).testTag(tag),
    )
}

@Composable
internal fun MenuSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(IdeColors.Separator),
    )
}

internal fun Modifier.activeUnderline(active: Boolean): Modifier =
    drawBehind {
        if (active) drawRect(IdeColors.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
    }
