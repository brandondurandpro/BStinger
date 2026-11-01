package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.FileDialog
import org.example.kotlineditor.state.ProjectState

@Composable
internal fun TreeContextMenu(project: ProjectState, entry: FileEntry, offset: DpOffset, onDismiss: () -> Unit) {
    val tree = project.tree
    val directory = if (entry.isDirectory) entry else tree.parentOf(entry) ?: tree.root
    val isRoot = entry.path == tree.root.path
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.testTag(Tags.TREE_MENU),
    ) {
        MenuItem(
            "New File…",
            Tags.MENU_NEW_FILE,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = false) } }
        MenuItem(
            "New Directory…",
            Tags.MENU_NEW_DIRECTORY,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = true) } }
        if (!isRoot) {
            MenuItem("Rename…", Tags.MENU_RENAME) { action { project.fileDialog = FileDialog.Rename(entry) } }
            MenuItem("Delete…", Tags.MENU_DELETE) { action { project.fileDialog = FileDialog.Delete(entry) } }
        }
        MenuItem("Copy Path", Tags.MENU_COPY_PATH) { action { TextClipboard.set(entry.path) } }
        MenuItem("Reload from Disk", Tags.MENU_RELOAD) { action { tree.refresh() } }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 13.sp) },
        onClick = onClick,
        modifier = Modifier.height(30.dp).testTag(tag),
    )
}
