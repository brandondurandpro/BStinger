package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val MENU_WIDTH = 340.dp
private val MENU_LIST_HEIGHT = 380.dp
private val MENU_ROW_HEIGHT = 32.dp
private val MENU_HEADER_HEIGHT = 24.dp

// The search field and the folder filter stay on top, the commands scroll with the name of their folder sticking to
// the top, then the actions on the configurations.
@Composable
private fun RunMenu(run: RunState, showWindow: () -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val start = { configuration: RunConfiguration ->
        close()
        run.select(configuration.id)
        showWindow()
        run.runSelected()
    }
    Row(Modifier.width(MENU_WIDTH).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IdeTextField(
            query,
            { query = it },
            Modifier.weight(1f).testTag(RunTags.WIDGET_SEARCH),
            placeholder = "Search commands",
            onSubmit = { run.search(query).firstOrNull()?.let(start) },
        )
        FolderFilter(run)
    }
    CommandList(run, query, showWindow, close, start)
    HorizontalDivider(color = IdeColors.Separator)
    ConfigurationActions(run, showWindow, close)
}

@Composable
private fun CommandList(
    run: RunState,
    query: String,
    showWindow: () -> Unit,
    close: () -> Unit,
    start: (RunConfiguration) -> Unit,
) {
    val recent = if (query.isBlank()) run.recentByFolder else emptyList()
    val folders = run.byFolder(query)
    if (query.isNotBlank() && folders.isEmpty()) {
        Label(
            "No command matches",
            Modifier.padding(12.dp).testTag(RunTags.WIDGET_NO_MATCH),
            color = IdeColors.TextSecondary,
        )
    }
    // A lazy list cannot be measured by its content inside a menu: it gets a fixed height, the one its rows need
    // up to a maximum, so that a project with hundreds of commands does not fill the screen.
    val sections = recent.size + folders.size + (if (recent.isEmpty()) 0 else 1) + if (folders.isEmpty()) 0 else 1
    val rows = recent.sumOf { it.second.size } + folders.sumOf { it.second.size }
    val wanted = MENU_ROW_HEIGHT * rows + MENU_HEADER_HEIGHT * sections
    val height = minOf(MENU_LIST_HEIGHT, wanted)
    LazyColumn(Modifier.size(MENU_WIDTH, height)) {
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recent", RunTags.widgetFolder(RECENT_SECTION)) }
            recent.forEach { (folder, configurations) ->
                stickyHeader { FolderTitle(folder, RunTags.widgetRecentFolder(folder), indent = true) }
                items(configurations, key = { "recent:${it.id}" }) { configuration ->
                    val tag = RunTags.widgetRecent(configuration.id)
                    ConfigurationEntry(run, configuration, tag, showWindow, close, start)
                }
            }
            item { HorizontalDivider(color = IdeColors.Separator) }
        }
        if (folders.isNotEmpty()) item { SectionTitle("By folder", RunTags.WIDGET_BY_FOLDER) }
        folders.forEach { (folder, configurations) ->
            stickyHeader { FolderTitle(folder, RunTags.widgetFolder(folder), indent = true) }
            items(configurations, key = { it.id }) { configuration ->
                ConfigurationEntry(run, configuration, RunTags.widgetItem(configuration.id), showWindow, close, start)
            }
        }
    }
}

// The name of a folder: opaque, so that it hides the commands that scroll under it.
@Composable
private fun FolderTitle(folder: String, tag: String, indent: Boolean) {
    Box(Modifier.fillMaxWidth().background(IdeColors.Popup)) {
        Label(
            folder.ifEmpty { "Project" },
            Modifier.padding(start = if (indent) 20.dp else 12.dp, top = 4.dp, bottom = 4.dp).testTag(tag),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
internal fun MenuEntry(
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
