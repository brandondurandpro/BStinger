package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.FindInFilesHost
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.selectedText

/** The Search tool window: Find in Files in a panel of the left dock, its button in the left strip. */
fun searchToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Search, Dock.Left),
        tag = TreeTags.SEARCH_TOOL_BUTTON,
        group = StripGroup.Top,
        icon = Lucide.Search,
        content = { host, drag, modifier -> FindInFilesPanel(host as FindInFilesHost, drag, modifier) },
    )

/**
 * Find in Files as a panel: the engine, the query field, the chips and the results of the popup, kept beside the
 * editors. A click on a hit opens it and leaves the panel (and its results) where it is.
 */
@Composable
fun FindInFilesPanel(project: FindInFilesHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val search = project.findInFiles
    val options = remember { FindChips() }
    var query by remember { mutableStateOf("") }
    val cursor = remember { ListCursor() }
    DriveSearch(project, search, query, options, cursor)
    val hide = { project.toolWindows.setVisible(ToolWindow.Search, false) }
    // Ctrl+Shift+F: the text selected in the editor, when on one line, becomes the query, like the modal did.
    LaunchedEffect(search.focusRequests) {
        val selected = selectionOf(project)
        if (search.focusRequests > 0 && selected.isNotEmpty()) query = selected
    }
    // Opening the panel makes room for it: the other windows of the left dock are hidden, not squeezed.
    LaunchedEffect(Unit) { hideOtherLeftWindows(project) }
    val groups = remember(search.results) { groupByFile(search.results) }
    // The keyboard walks through the hits of the files that are not folded.
    val visible = visibleHits(groups, options.collapsed.toList())
    val open = { hit: SearchHit? ->
        hit?.let {
            // The row that was opened stays selected, by click as by keyboard.
            val index = visible.indexOf(it)
            if (index >= 0) cursor.selected = index
            val start = TextPos(it.line, it.column)
            val end = TextPos(it.line, it.column + matchLength(optionsOf(query, options), it))
            project.editor.openAt(it.entry, start, end)
        }
        Unit
    }
    Column(modifier.fillMaxSize().background(IdeColors.Panel).testTag(TreeTags.SEARCH_PANEL)) {
        ToolWindowHeader("Search", ToolWindow.Search, project.toolWindows, drag) {
            IconButton(hide) { CloseIcon() }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            FindField(query, { query = it }, options, visible, cursor, open, hide, search.focusRequests)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(
                    status(search, query),
                    Modifier.testTag(TreeTags.FIND_IN_FILES_COUNT),
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                )
                engineLabel(search)
            }
            OptionsSection(options)
            ResultsSection(search, groups, query, options, cursor, open, Modifier.weight(1f))
        }
    }
}

// Every other window docked at the left and shown is hidden; their buttons stay in the strip to bring them back.
private fun hideOtherLeftWindows(project: FindInFilesHost) {
    val layout = project.toolWindows
    val others = layout.visibleIn(Dock.Left).filter { it != ToolWindow.Search }
    others.forEach { layout.setVisible(it, false) }
}

// The selected text of the active document, only when it is on one line.
private fun selectionOf(project: FindInFilesHost): String {
    val document = project.editor.activeTab?.document
    val selected = document?.selectedText().orEmpty()
    return selected.takeIf { '\n' !in it }.orEmpty()
}
