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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.EditorState
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.relativePath

/** Editor tabs: active one underlined, close button on hover, middle click closes. */
@Composable
fun EditorTabs(editor: EditorState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val active = editor.activeTab
    LaunchedEffect(active) {
        val index = editor.tabs.indexOf(active)
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
            items(editor.tabs, key = { it.key }) { tab ->
                EditorTabView(
                    tab,
                    active = tab == active,
                    onSelect = { editor.activate(tab) },
                    onClose = { editor.close(tab) },
                )
            }
        }
        HorizontalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp),
        )
    }
}

private fun isFullyVisible(listState: LazyListState, index: Int): Boolean {
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
private fun EditorTabView(tab: EditorTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val modified = tab.document?.isModified == true
    val tags = tagsOf(tab)
    Row(
        Modifier
            .fillMaxHeight()
            .background(if (hovered && !active) IdeColors.Hover else Color.Transparent)
            .activeUnderline(active)
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event -> onTabPress(event, onSelect, onClose) }
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

// Middle click closes the tab, like in IntelliJ and browsers.
@OptIn(ExperimentalComposeUiApi::class)
private fun onTabPress(event: PointerEvent, onSelect: () -> Unit, onClose: () -> Unit) {
    when {
        event.button == PointerButton.Tertiary || event.buttons.isTertiaryPressed -> onClose()
        event.buttons.isPrimaryPressed -> onSelect()
        else -> Unit
    }
}

private fun Modifier.activeUnderline(active: Boolean): Modifier =
    drawBehind {
        if (active) drawRect(IdeColors.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
    }
