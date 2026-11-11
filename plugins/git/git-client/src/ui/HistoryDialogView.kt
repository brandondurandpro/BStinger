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

/**
 * The dialog of a context menu of the Branches window: an optional name to type, a confirmation. Enter confirms and
 * Escape (or the backdrop) cancels; a failure stays here with Git's reason, so that the user can fix the name.
 */
@Composable
internal fun HistoryDialogView(project: GitHost, dialog: HistoryDialog, onClose: () -> Unit) {
    var text by remember(dialog) { mutableStateOf(dialog.input.orEmpty()) }
    var running by remember(dialog) { mutableStateOf(false) }
    var failure by remember(dialog) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // The name is selected when the dialog opens, so that typing replaces it (a rename) or fills it (a new name).
    var selectRequest by remember(dialog) { mutableIntStateOf(0) }
    LaunchedEffect(dialog) {
        runCatching { focus.requestFocus() }
        selectRequest++
    }
    val needsName = dialog.input != null
    val ready = !running && !project.git.busy && (!needsName || text.isNotBlank())
    val confirm = {
        if (ready) {
            running = true
            failure = null
            dialog.submit(text.trim()) { worked ->
                running = false
                if (worked) onClose() else failure = project.git.message ?: "Git command failed"
            }
        }
    }
    PopupFrame(GitTags.HISTORY_DIALOG, onClose = { if (!running) onClose() }, width = DIALOG_WIDTH) {
        Column(
            Modifier
                .padding(4.dp)
                .popupKeys(onEnter = confirm, onClose = { if (!running) onClose() }),
        ) {
            val titleModifier = if (needsName) Modifier else Modifier.focusRequester(focus).focusable()
            Label(dialog.title, titleModifier, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            dialog.message?.let {
                Label(it, Modifier.padding(top = 4.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
            }
            if (needsName) {
                IdeTextField(
                    text,
                    { text = it },
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
            failure?.let {
                Label(
                    it,
                    Modifier.padding(top = 6.dp).testTag(GitTags.HISTORY_DIALOG_ERROR),
                    color = IdeColors.Error,
                    fontSize = 11.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                IdeButton(
                    "Cancel",
                    { if (!running) onClose() },
                    Modifier.testTag(GitTags.HISTORY_DIALOG_CANCEL),
                    enabled = !running,
                )
                IdeButton(
                    dialog.confirmLabel,
                    { confirm() },
                    Modifier.testTag(GitTags.HISTORY_DIALOG_CONFIRM),
                    variant = if (dialog.danger) ButtonVariant.Destructive else ButtonVariant.Default,
                    enabled = ready,
                )
            }
        }
    }
}
