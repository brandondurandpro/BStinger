package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.FolderPickerState

/**
 * Remote folder picker: the SSH user's home first, then one level of subfolders at a time. A click enters a folder;
 * *Open* chooses the current one. *Up* and *Home* move around, and the path field accepts an absolute path or `~`.
 */
@Composable
fun FolderPickerDialog(app: AppState, state: FolderPickerState) {
    val close = app::closeFolderPicker
    PopupFrame(Tags.FOLDER_PICKER, close, width = 620) {
        Column {
            Label("Open Folder", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Label(
                "Remote: ${app.remoteLabel ?: "server"} · Home: ${app.remoteHome ?: "?"}",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
            )
            NavigationRow(state, close)
            state.error?.let {
                Label(
                    it,
                    color = IdeColors.Error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp).testTag(Tags.FOLDER_PICKER_ERROR),
                )
            }
            FolderList(state)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                IdeButton("Cancel", close, Modifier.testTag(Tags.FOLDER_PICKER_CANCEL))
                IdeButton(
                    "Open",
                    { state.choose()?.let(app::openPickedFolder) },
                    Modifier.padding(start = 8.dp).testTag(Tags.FOLDER_PICKER_OPEN),
                    primary = true,
                    enabled = state.canChoose,
                )
            }
        }
    }
}

@Composable
private fun NavigationRow(state: FolderPickerState, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IdeButton(
            "Home",
            state::goHome,
            Modifier.testTag(Tags.FOLDER_PICKER_HOME),
            enabled = state.canGoHome && !state.loading,
        )
        IdeButton(
            "Up",
            state::goUp,
            Modifier.testTag(Tags.FOLDER_PICKER_UP),
            enabled = state.canGoUp && !state.loading,
        )
        IdeTextField(
            state.path,
            state::editPath,
            Modifier.weight(1f).testTag(Tags.FOLDER_PICKER_PATH).popupKeys(state::submitPath, onClose),
            placeholder = "/home/user/project",
            onSubmit = state::submitPath,
            monospace = true,
        )
    }
}

@Composable
private fun FolderList(state: FolderPickerState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.current?.path) { runCatching { listState.scrollToItem(0) } }
    when {
        state.loading -> {
            Label(
                "Loading…",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(Tags.FOLDER_PICKER_LOADING),
            )
        }

        state.checking -> {
            Label(
                "Checking…",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(Tags.FOLDER_PICKER_CHECKING),
            )
        }

        state.current != null -> {
            Label(
                if (state.entries.size == 1) "1 folder" else "${state.entries.size} folders",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).testTag(Tags.FOLDER_PICKER_COUNT),
            )
        }
    }
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), state = listState) {
            items(state.entries, key = { it.path }) { entry ->
                FolderRow(entry) { state.enter(entry) }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
    if (!state.loading && state.current != null && state.entries.isEmpty()) {
        Label(
            "No subfolders",
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp).testTag(Tags.FOLDER_PICKER_EMPTY),
        )
    }
}

@Composable
private fun FolderRow(entry: FileEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) IdeColors.Hover else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .testTag(ItemTags.folderEntry(entry.path))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderIcon(Modifier.size(16.dp))
        Label(entry.name, Modifier.padding(start = 8.dp))
    }
}
