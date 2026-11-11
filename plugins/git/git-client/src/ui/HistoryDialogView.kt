package org.example.kotlineditor.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.GitHost
import org.example.kotlineditor.state.HistoryDialog

private const val DIALOG_WIDTH = 420

// What the user typed, and whether the action is running or failed: the dialog stays open on a failure.
private class DialogProgress(dialog: HistoryDialog) {
    var text by mutableStateOf(dialog.input.orEmpty())
    var running by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)

    fun submit(project: GitHost, dialog: HistoryDialog, onClose: () -> Unit) {
        running = true
        failure = null
        dialog.submit(text.trim()) { worked ->
            running = false
            if (worked) onClose() else failure = project.git.message ?: "Git command failed"
        }
    }
}

/**
 * The dialog of a context menu of the Branches window: an optional name to type, a confirmation. Enter confirms and
 * Escape (or the backdrop) cancels; a failure stays here with Git's reason, so that the user can fix the name.
 */
@Composable
internal fun HistoryDialogView(project: GitHost, dialog: HistoryDialog, onClose: () -> Unit) {
    val progress = remember(dialog) { DialogProgress(dialog) }
    val focus = remember { FocusRequester() }
    // The name is selected when the dialog opens, so that typing replaces it (a rename) or fills it (a new name).
    var selectRequest by remember(dialog) { mutableIntStateOf(0) }
    LaunchedEffect(dialog) {
        runCatching { focus.requestFocus() }
        selectRequest++
    }
    val nameMissing = dialog.input != null && progress.text.isBlank()
    val ready = !progress.running && !project.git.busy && !nameMissing
    val confirm = { if (ready) progress.submit(project, dialog, onClose) }
    val cancel = { if (!progress.running) onClose() }
    PopupFrame(GitTags.HISTORY_DIALOG, onClose = cancel, width = DIALOG_WIDTH) {
        Column(Modifier.padding(4.dp).popupKeys(onEnter = confirm, onClose = cancel)) {
            DialogTexts(dialog, progress, focus, selectRequest, confirm)
            DialogButtons(dialog, progress.running, ready, confirm, cancel)
        }
    }
}

@Composable
private fun DialogTexts(
    dialog: HistoryDialog,
    progress: DialogProgress,
    focus: FocusRequester,
    selectRequest: Int,
    confirm: () -> Unit,
) {
    // Without a field, the title takes the focus, so that Enter and Escape reach the dialog.
    val titleModifier = if (dialog.input == null) Modifier.focusRequester(focus).focusable() else Modifier
    Label(dialog.title, titleModifier, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    dialog.message?.let { Label(it, Modifier.padding(top = 4.dp), color = IdeColors.TextSecondary, fontSize = 11.sp) }
    if (dialog.input != null) {
        IdeTextField(
            progress.text,
            { progress.text = it },
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .focusRequester(focus)
                .testTag(GitTags.HISTORY_DIALOG_INPUT),
            placeholder = "Name",
            onSubmit = confirm,
            selectAllRequest = selectRequest,
        )
    }
    progress.failure?.let {
        val modifier = Modifier.padding(top = 6.dp).testTag(GitTags.HISTORY_DIALOG_ERROR)
        Label(it, modifier, color = IdeColors.Error, fontSize = 11.sp)
    }
}

@Composable
private fun DialogButtons(
    dialog: HistoryDialog,
    running: Boolean,
    ready: Boolean,
    confirm: () -> Unit,
    cancel: () -> Unit,
) {
    val variant = if (dialog.danger) ButtonVariant.Destructive else ButtonVariant.Default
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        IdeButton("Cancel", cancel, Modifier.testTag(GitTags.HISTORY_DIALOG_CANCEL), enabled = !running)
        IdeButton(
            dialog.confirmLabel,
            confirm,
            Modifier.testTag(GitTags.HISTORY_DIALOG_CONFIRM),
            variant = variant,
            enabled = ready,
        )
    }
}
