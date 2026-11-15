package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.kotlineditor.state.CommandLineStatus
import org.example.kotlineditor.state.CommandLineTool

/** Installs or removes the `ide` command of the shell, and says where it is and what is left for the user to do. */
@Composable
internal fun CommandLineControl(tool: CommandLineTool) {
    val status = tool.status
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(
            statusText(status, tool),
            Modifier.testTag(AppTags.SETTINGS_COMMAND_LINE_STATUS),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canInstall = status == CommandLineStatus.NotInstalled || status == CommandLineStatus.Outdated
            IdeButton(
                if (status == CommandLineStatus.Outdated) "Update" else "Install",
                // The PowerShell that edits the PATH of Windows takes seconds: not on the thread of the window.
                { scope.launch(Dispatchers.IO) { tool.install() } },
                Modifier.testTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL),
                primary = canInstall,
                enabled = canInstall,
            )
            IdeButton(
                "Remove",
                { scope.launch(Dispatchers.IO) { tool.remove() } },
                Modifier.testTag(AppTags.SETTINGS_COMMAND_LINE_REMOVE),
                enabled = status == CommandLineStatus.Installed || status == CommandLineStatus.Outdated,
            )
        }
        tool.message?.let {
            Label(
                it,
                Modifier.testTag(AppTags.SETTINGS_COMMAND_LINE_MESSAGE),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 4,
            )
        }
    }
}

private fun statusText(status: CommandLineStatus, tool: CommandLineTool): String =
    when (status) {
        CommandLineStatus.Unavailable -> "Only in the installed app: this IDE does not know where its launcher is."
        CommandLineStatus.NotInstalled -> "Not installed. It will be written to ${tool.scriptPath}."
        CommandLineStatus.Installed -> "Installed: ${tool.scriptPath}."
        CommandLineStatus.Outdated -> "${tool.scriptPath} starts another copy of the app: update it to start this one."
        CommandLineStatus.Foreign -> "${tool.scriptPath} exists and is not ours: it is left untouched."
    }
