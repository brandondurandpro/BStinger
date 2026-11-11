package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.RunState

private val FILTER_LIST_HEIGHT = 300.dp
private val FILTER_WIDTH = 260.dp

// The filter icon: its menu lists the folders with a check box each, and the commands shown are those of the checked
// ones (all of them when none is checked).
@Composable
internal fun FolderFilter(run: RunState) {
    var open by remember { mutableStateOf(false) }
    val active = run.folderFilter.isNotEmpty()
    Box(Modifier.padding(start = 4.dp)) {
        IconButton({ open = true }, Modifier.testTag(RunTags.WIDGET_FILTER)) {
            IdeIcon(Lucide.ListFilter, color = if (active) IdeColors.Link else IdeColors.TextSecondary)
        }
        IdeDropdownMenu(open, { open = false }, Modifier.testTag(RunTags.WIDGET_FILTER_MENU)) {
            FolderFilterMenu(run)
        }
    }
}

// A search field, the quick actions (they act on the folders the search leaves), then the folders with a check box.
@Composable
private fun FolderFilterMenu(run: RunState) {
    var query by remember { mutableStateOf("") }
    val shown = run.searchFolders(query)
    IdeTextField(
        query,
        { query = it },
        Modifier.width(FILTER_WIDTH).padding(8.dp).testTag(RunTags.WIDGET_FILTER_SEARCH),
        placeholder = "Search folders",
    )
    MenuEntry("Select all", RunTags.WIDGET_FILTER_SELECT_ALL) { run.selectAllFolders(shown) }
    MenuEntry("Clear", RunTags.WIDGET_FILTER_ALL) { run.clearFolderFilter() }
    HorizontalDivider(color = IdeColors.Separator)
    if (shown.isEmpty()) {
        Label(
            "No folder matches",
            Modifier.padding(12.dp).testTag(RunTags.WIDGET_FILTER_NONE),
            color = IdeColors.TextSecondary,
        )
    }
    Column(Modifier.heightIn(max = FILTER_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
        shown.forEach { folder -> FolderCheckRow(run, folder) }
    }
}

// A folder of the filter menu with its check box.
@Composable
private fun FolderCheckRow(run: RunState, folder: String) {
    val checked = folder in run.folderFilter
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { run.toggleFolder(folder) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(RunTags.widgetFilter(folder)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp)) {
            if (checked) IdeIcon(Lucide.Check, color = IdeColors.Link)
        }
        Label(folder.ifEmpty { "Project" }, Modifier.padding(start = 8.dp))
    }
}
