package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun isFullyVisible(listState: LazyListState, index: Int): Boolean {
    val info = listState.layoutInfo
    return info.visibleItemsInfo.any {
        it.index == index &&
            it.offset >= 0 &&
            it.offset + it.size <= info.viewportEndOffset
    }
}

// Middle click closes the tab, like in IntelliJ and browsers; right click opens its context menu.
@OptIn(ExperimentalComposeUiApi::class)
fun onTabPress(event: PointerEvent, onSelect: () -> Unit, onClose: () -> Unit, onContextMenu: (Offset) -> Unit) {
    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
    when {
        event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed -> onContextMenu(position)
        event.button == PointerButton.Tertiary || event.buttons.isTertiaryPressed -> onClose()
        event.buttons.isPrimaryPressed -> onSelect()
        else -> Unit
    }
}

@Composable
fun TabMenuItem(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 13.sp) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(30.dp).testTag(tag),
    )
}

/** What the context menu of a tab closes: the tab, the others, those on its sides, or all of them. */
class CloseTabsActions(
    val close: () -> Unit,
    val closeOthers: () -> Unit,
    val closeToLeft: () -> Unit,
    val closeToRight: () -> Unit,
    val closeAll: () -> Unit,
)

/** Context menu of the tab at [index] among [count]: Close, Close Others, Close All to the Left / Right, Close All. */
@Composable
fun CloseTabsMenu(
    index: Int,
    count: Int,
    actions: CloseTabsActions,
    offset: DpOffset,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    // The menu opens under its anchor: a point-sized one at the click puts it right there.
    Box(Modifier.offset(offset.x, offset.y).size(0.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = modifier) {
            TabMenuItem("Close", Tags.MENU_CLOSE_TAB) { action(actions.close) }
            TabMenuItem("Close Others", Tags.MENU_CLOSE_OTHER_TABS, enabled = count > 1) { action(actions.closeOthers) }
            TabMenuItem("Close All to the Left", Tags.MENU_CLOSE_TABS_LEFT, enabled = index > 0) {
                action(actions.closeToLeft)
            }
            TabMenuItem("Close All to the Right", Tags.MENU_CLOSE_TABS_RIGHT, enabled = index in 0 until count - 1) {
                action(actions.closeToRight)
            }
            MenuSeparator()
            TabMenuItem("Close All", Tags.MENU_CLOSE_ALL_TABS) { action(actions.closeAll) }
            extra()
        }
    }
}

@Composable
fun MenuSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(IdeColors.Separator),
    )
}
