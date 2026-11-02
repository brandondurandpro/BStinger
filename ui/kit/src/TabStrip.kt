package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
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
