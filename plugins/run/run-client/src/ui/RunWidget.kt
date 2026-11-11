package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import org.example.kotlineditor.state.RunConfiguration
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
    val running = run.anyRunning
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
        if (running) RunningBadge(Modifier.padding(start = 8.dp).testTag(RunTags.WIDGET_STATUS))
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

// The search field, the recent commands, the commands by folder, then the actions on the configurations.
@Composable
private fun RunMenu(run: RunState, showWindow: () -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val start = { configuration: RunConfiguration ->
        close()
        run.select(configuration.id)
        showWindow()
        run.runSelected()
    }
    IdeTextField(
        query,
        { query = it },
        Modifier.fillMaxWidth().padding(8.dp).testTag(RunTags.WIDGET_SEARCH),
        placeholder = "Search commands",
        onSubmit = { run.search(query).firstOrNull()?.let(start) },
    )
    val recent = if (searching) emptyList() else run.recentByFolder
    if (recent.isNotEmpty()) {
        SectionTitle("Recent", RunTags.widgetFolder(RECENT_SECTION))
        recent.forEach { (folder, configurations) ->
            val title = folder.ifEmpty { "Project" }
            Label(
                title,
                Modifier.padding(start = 20.dp, top = 2.dp).testTag(RunTags.widgetRecentFolder(folder)),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
            configurations.forEach { configuration ->
                ConfigurationEntry(run, configuration, RunTags.widgetRecent(configuration.id), showWindow, close, start)
            }
        }
        HorizontalDivider(color = IdeColors.Separator)
    }
    val folders = run.byFolder(query)
    if (searching && folders.isEmpty()) {
        Label(
            "No command matches",
            Modifier.padding(12.dp).testTag(RunTags.WIDGET_NO_MATCH),
            color = IdeColors.TextSecondary,
        )
    }
    folders.forEachIndexed { index, (folder, configurations) ->
        if (index > 0) HorizontalDivider(color = IdeColors.Separator)
        SectionTitle(folder.ifEmpty { "Project" }, RunTags.widgetFolder(folder))
        configurations.forEach { configuration ->
            ConfigurationEntry(run, configuration, RunTags.widgetItem(configuration.id), showWindow, close, start)
        }
    }
    if (folders.isNotEmpty()) HorizontalDivider(color = IdeColors.Separator)
    ConfigurationActions(run, showWindow, close)
}

@Composable
private fun ConfigurationActions(run: RunState, showWindow: () -> Unit, close: () -> Unit) {
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

private const val RECENT_SECTION = "\u0000recent"

@Composable
private fun SectionTitle(text: String, tag: String) {
    Label(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag(tag),
        color = IdeColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// A command of the menu: click to run it, the pencil edits it and the bin deletes it.
@Composable
private fun ConfigurationEntry(
    run: RunState,
    configuration: RunConfiguration,
    tag: String,
    showWindow: () -> Unit,
    close: () -> Unit,
    start: (RunConfiguration) -> Unit,
) {
    MenuEntry(
        configuration.name,
        tag,
        indent = true,
        running = run.isRunning(configuration.id),
        onEdit = {
            close()
            showWindow()
            run.edit(configuration.id)
        },
        onDelete = { run.delete(configuration.id) },
    ) {
        start(configuration)
    }
}

@Composable
private fun MenuEntry(
    text: String,
    tag: String,
    indent: Boolean = false,
    running: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 24.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label =
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp)
                .testTag(tag)
        Box(label) { Label(text) }
        if (running) RunningBadge(Modifier.padding(horizontal = 8.dp).testTag(RunTags.widgetRunning(tag)))
        if (onEdit != null) {
            IconButton(onEdit, Modifier.testTag(RunTags.editOf(tag))) { IdeIcon(Lucide.Pencil, size = 14.dp) }
        }
        if (onDelete != null) {
            IconButton(onDelete, Modifier.testTag(RunTags.deleteOf(tag))) { IdeIcon(Lucide.Trash2, size = 14.dp) }
        }
    }
}

// The green dot and "Running" of a command in progress.
@Composable
private fun RunningBadge(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(IdeColors.GitAdded, CircleShape))
        Label("Running", Modifier.padding(start = 4.dp), color = IdeColors.GitAdded, fontSize = 11.sp)
    }
}
