package org.example.kotlineditor.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.FileDialog
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.createFile
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.rename

/** Go to Line:Column (Ctrl+G). */
@Composable
fun GotoLinePopup(document: Document, onClose: () -> Unit) {
    var value by remember { mutableStateOf("${document.caret.line + 1}:${document.caret.column + 1}") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val go = {
        val parts = value.split(':')
        parts.getOrNull(0)?.trim()?.toIntOrNull()?.let { line ->
            val column = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            document.moveTo(TextPos(line - 1, column - 1))
            onClose()
        }
        Unit
    }
    PopupFrame(Tags.GOTO_LINE, onClose, width = 360) {
        Column {
            Label("Go to Line:Column", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            IdeTextField(
                value,
                { value = it },
                Modifier
                    .fillMaxWidth()
                    .testTag(Tags.GOTO_LINE_INPUT)
                    .focusRequester(focus)
                    .popupKeys(go, onClose),
                placeholder = "line[:column]",
            )
            Label(
                "${document.lineCount} lines",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** New File, New Directory, Rename and Delete dialogs of the project tree. */
@Composable
fun FileDialogPopup(project: ProjectState, dialog: FileDialog, onClose: () -> Unit) {
    var name by remember(dialog::class) { mutableStateOf((dialog as? FileDialog.Rename)?.entry?.name.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { submit(project, dialog, name) }
    PopupFrame(Tags.FILE_DIALOG, onClose, width = 420) {
        Column {
            Label(title(dialog), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            if (dialog is FileDialog.Delete) {
                val kind = if (dialog.entry.isDirectory) "directory" else "file"
                Label(
                    "Delete $kind '${dialog.entry.name}'?",
                    Modifier.focusRequester(focus).focusable().popupKeys(submit, onClose),
                )
            } else {
                IdeTextField(
                    name,
                    { name = it },
                    Modifier
                        .fillMaxWidth()
                        .testTag(Tags.FILE_DIALOG_INPUT)
                        .focusRequester(focus)
                        .popupKeys(submit, onClose),
                    placeholder =
                    if (dialog is FileDialog.Create &&
                        !dialog.isDirectory
                    ) {
                        "Name, e.g. src/Main.kt"
                    } else {
                        "Name"
                    },
                )
            }
            dialog.error?.let {
                Label(
                    it,
                    color = IdeColors.Error,
                    modifier = Modifier.padding(top = 6.dp).testTag(Tags.FILE_DIALOG_ERROR),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                IdeButton("Cancel", onClose)
                IdeButton(
                    action(dialog),
                    submit,
                    Modifier.padding(start = 8.dp).testTag(Tags.FILE_DIALOG_OK),
                    primary = true,
                )
            }
        }
    }
}

private fun title(dialog: FileDialog): String =
    when (dialog) {
        is FileDialog.Create -> if (dialog.isDirectory) "New Directory" else "New File"
        is FileDialog.Rename -> "Rename"
        is FileDialog.Delete -> "Delete"
    }

private fun action(dialog: FileDialog): String =
    when (dialog) {
        is FileDialog.Create -> "OK"
        is FileDialog.Rename -> "Refactor"
        is FileDialog.Delete -> "Delete"
    }

private fun submit(project: ProjectState, dialog: FileDialog, name: String) =
    when (dialog) {
        is FileDialog.Create -> project.createFile(dialog.directory, name, dialog.isDirectory)
        is FileDialog.Rename -> project.rename(dialog.entry, name)
        is FileDialog.Delete -> project.delete(dialog.entry)
    }
