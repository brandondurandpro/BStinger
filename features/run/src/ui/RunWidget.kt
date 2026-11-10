package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.RunState

private val WIDGET_NAME_WIDTH = 220.dp

/**
 * Run widget of the toolbar (top right): the configuration that Run starts, with a menu of every command the project
 * offers grouped by folder, the button that starts it and the one that stops it. Choosing a command in the menu runs
 * it; [showWindow] shows the Run tool window, where its output goes.
 */
@Composable
fun RunWidget(run: RunState, showWindow: () -> Unit, modifier: Modifier = Modifier) {
    // The commands are looked for as soon as the project is open, not only once the Run window was.
    LaunchedEffect(run) { run.ensureLoaded() }
    var open by remember { mutableStateOf(false) }
    val running = run.session?.running == true
    Row(modifier.testTag(RunTags.WIDGET), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier
                    .background(IdeColors.Hover, IdeShapes.Medium)
                    .clickable { open = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag(RunTags.WIDGET_SELECTOR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    run.selected?.name ?: "Add configuration",
                    Modifier.widthIn(max = WIDGET_NAME_WIDTH).padding(end = 4.dp),
                )
                Chevron(expanded = open)
            }
            IdeDropdownMenu(open, { open = false }, Modifier.testTag(RunTags.WIDGET_MENU)) {
                RunMenu(run, showWindow) { open = false }
            }
        }
        IconButton(
            {
                if (run.selected != null) {
                    showWindow()
                    run.runSelected()
                }
            },
            Modifier.padding(start = 4.dp).testTag(RunTags.WIDGET_START),
        ) {
            PlayIcon(color = if (run.selected != null) IdeColors.GitAdded else IdeColors.TextSecondary)
        }
        IconButton({ run.stop() }, Modifier.testTag(RunTags.WIDGET_STOP)) {
            StopIcon(color = if (running) IdeColors.Error else IdeColors.TextSecondary)
        }
    }
}

// The commands by folder, then the actions on the configurations.
@Composable
private fun RunMenu(run: RunState, showWindow: () -> Unit, close: () -> Unit) {
    val folders = run.byFolder
    folders.forEachIndexed { index, (folder, configurations) ->
        if (index > 0) HorizontalDivider(color = IdeColors.Separator)
        Label(
            folder.ifEmpty { "Project" },
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag(RunTags.widgetFolder(folder)),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        configurations.forEach { configuration ->
            MenuEntry(configuration.name, RunTags.widgetItem(configuration.id), indent = true) {
                close()
                run.select(configuration.id)
                showWindow()
                run.runSelected()
            }
        }
    }
    if (folders.isNotEmpty()) HorizontalDivider(color = IdeColors.Separator)
    MenuEntry("New configuration…", RunTags.WIDGET_NEW) {
        close()
        showWindow()
        run.newConfiguration()
    }
    if (run.selected != null) {
        MenuEntry("Edit selected…", RunTags.WIDGET_EDIT) {
            close()
            showWindow()
            run.editSelected()
        }
    }
    MenuEntry("Show the Run window", RunTags.WIDGET_SHOW) {
        close()
        showWindow()
    }
}

@Composable
private fun MenuEntry(text: String, tag: String, indent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 24.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .testTag(tag),
    ) {
        Label(text)
    }
}
