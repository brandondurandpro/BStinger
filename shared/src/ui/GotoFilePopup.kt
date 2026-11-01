package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.FileIndex
import org.example.kotlineditor.state.FileMatch
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.relativePath

/** Go to File (Ctrl+P or Ctrl+Shift+N) or, with [recentOnly], Recent Files (Ctrl+E). */
@Composable
fun GotoFilePopup(project: ProjectState, onClose: () -> Unit, recentOnly: Boolean = false) {
    var query by remember { mutableStateOf("") }
    val found = remember { FoundFiles() }
    val cursor = found.cursor
    val focus = remember { FocusRequester() }
    val indexed = if (recentOnly) emptyList() else project.fileIndex.files
    val open: (FileMatch) -> Unit = { match ->
        onClose()
        project.editor.open(match.entry)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, indexed) {
        val results = matches(project, query, recentOnly)
        found.update(query, indexed, results, firstSelection(recentOnly, query, results), open)
    }
    val results = found.results
    PopupFrame(if (recentOnly) Tags.RECENT_FILES else Tags.GOTO_FILE, onClose) {
        Column {
            Label(
                if (recentOnly) "Recent Files" else "Files",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .testTag(Tags.GOTO_FILE_INPUT)
                    .focusRequester(focus)
                    .popupKeys(
                        { found.enter(query, indexed, open) },
                        onClose,
                    ) { cursor.move(it, results.size) },
                placeholder = "Type a file name",
                leading = { SearchIcon() },
            )
            if (indexed == null) Label("Indexing…", color = IdeColors.TextSecondary, modifier = Modifier.padding(8.dp))
            ResultList(
                results,
                cursor.selected,
            ) { index, match -> FileMatchRow(match, index == cursor.selected) { open(match) } }
        }
    }
}

/**
 * The files the popup lists, and the query and index they were found for: they lag behind what is typed while the
 * search runs. Enter pressed before the results of the typed query opens their selection once they arrive, never a
 * file of the previous list.
 */
private class FoundFiles {
    var results by mutableStateOf<List<FileMatch>>(emptyList())
        private set
    val cursor = ListCursor()
    private var foundFor: Pair<String, List<*>?>? = null
    private var openWhenFound = false

    fun update(query: String, indexed: List<*>?, matches: List<FileMatch>, selection: Int, open: (FileMatch) -> Unit) {
        results = matches
        cursor.selected = selection
        foundFor = query to indexed
        // Results found before the index was ready are not the ones Enter asked for.
        if (openWhenFound && indexed != null) {
            openWhenFound = false
            selected()?.let(open)
        }
    }

    fun enter(query: String, indexed: List<*>?, open: (FileMatch) -> Unit) {
        if (indexed != null && foundFor == query to indexed) selected()?.let(open) else openWhenFound = true
    }

    private fun selected(): FileMatch? = results.getOrNull(cursor.selected)
}

private suspend fun matches(project: ProjectState, query: String, recentOnly: Boolean): List<FileMatch> =
    if (recentOnly) recentMatches(project, query) else project.fileIndex.searchFiles(query)

// Like IntelliJ, Ctrl+E then Enter goes back to the previous file.
private fun firstSelection(recentOnly: Boolean, query: String, results: List<FileMatch>): Int =
    if (recentOnly && query.isBlank() && results.size > 1) 1 else 0

@Composable
private fun FileMatchRow(match: FileMatch, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(ItemTags.gotoFileItem(match.relativePath))
            .resultRow(selected)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(match.entry.name, false)
        BasicText(
            highlightIndices(match.entry.name, match.matchedIndices),
            Modifier.padding(start = 6.dp),
            style = TextStyle(fontSize = 13.sp),
            maxLines = 1,
        )
        Label(
            match.relativePath.substringBeforeLast('/', ""),
            Modifier.padding(start = 10.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

private fun recentMatches(project: ProjectState, query: String): List<FileMatch> {
    val q = query.trim()
    return project.editor.recentFiles.mapNotNull { entry ->
        val match = if (q.isEmpty()) 0 to emptyList() else FileIndex.fuzzyMatch(q, entry.name)
        match?.let { (score, indices) -> FileMatch(entry, project.fileIndex.relativePath(entry), score, indices) }
    }
}
