package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.state.FileDialog
import org.example.kotlineditor.state.TreeHost

@Composable
internal fun TreeContextMenu(project: TreeHost, entry: FileEntry, offset: DpOffset, onDismiss: () -> Unit) {
    val tree = project.tree
    val directory = if (entry.isDirectory) entry else tree.parentOf(entry) ?: tree.root
    val isRoot = entry.path == tree.root.path
    val rootRelative = relativeToRoot(entry.path, tree.root.path)
    val relative = rootRelative.ifEmpty { "." }
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    IdeDropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.testTag(TreeTags.TREE_MENU),
    ) {
        MenuItem(
            "New File…",
            TreeTags.MENU_NEW_FILE,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = false) } }
        MenuItem(
            "New Directory…",
            TreeTags.MENU_NEW_DIRECTORY,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = true) } }
        if (!isRoot) {
            MenuItem("Rename…", TreeTags.MENU_RENAME) { action { project.fileDialog = FileDialog.Rename(entry) } }
            MenuItem("Delete…", TreeTags.MENU_DELETE) { action { project.fileDialog = FileDialog.Delete(entry) } }
        }
        MenuItem("Deploy to…", TreeTags.MENU_DEPLOY) {
            action { project.fileDialog = FileDialog.Deploy(listOf(entry)) }
        }
        MenuItem("Copy Absolute Path", TreeTags.MENU_COPY_PATH) { action { TextClipboard.set(entry.path) } }
        MenuItem("Copy Relative Path", TreeTags.MENU_COPY_RELATIVE_PATH) { action { TextClipboard.set(relative) } }
        MenuItem("Copy Name", TreeTags.MENU_COPY_NAME) { action { TextClipboard.set(entry.name) } }
        MenuItem("Reload from Disk", TreeTags.MENU_RELOAD) { action { tree.refresh() } }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    IdeMenuItem(
        text = { Text(text, fontSize = 13.sp) },
        onClick = onClick,
        modifier = Modifier.height(30.dp).testTag(tag),
    )
}
