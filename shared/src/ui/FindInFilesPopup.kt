package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.state.FindInFilesSearch
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.state.selectedText

private const val DEBOUNCE_MS = 150L

/** Find in Files (Ctrl+Shift+F): searched by the server, results shown as they arrive. */
@Composable
fun FindInFilesPopup(project: ProjectState, onClose: () -> Unit) {
    val initial =
        project.editor.activeTab
            ?.document
            ?.selectedText()
            ?.takeIf { '\n' !in it }
            .orEmpty()
    var query by remember { mutableStateOf(initial) }
    var matchCase by remember { mutableStateOf(false) }
    val search = remember { FindInFilesSearch(project.fileIndex, project.services.connection) }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, matchCase) {
        if (query.isNotEmpty()) delay(DEBOUNCE_MS) // restarted on every keystroke, which stops the search
        cursor.selected = 0
        search.run(SearchQuery(query, ignoreCase = !matchCase))
    }
    val list = search.hits
    KeepSelection(list, cursor)
    val open = { hit: SearchHit? ->
        hit?.let {
            onClose()
            project.editor.openAt(it.entry, TextPos(it.line, it.column), TextPos(it.line, it.column + query.length))
        }
        Unit
    }
    PopupFrame(Tags.FIND_IN_FILES, onClose, width = 760) {
        Column {
            Header(search, query)
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(Tags.FIND_IN_FILES_INPUT)
                    .focusRequester(focus)
                    .popupKeys({ open(list.getOrNull(cursor.selected)) }, onClose) { cursor.move(it, list.size) },
                placeholder = "Text to find",
                leading = { SearchIcon() },
                trailing = { ToggleChip("Cc", matchCase) { matchCase = !matchCase } },
            )
            ResultList(
                list,
                cursor.selected,
            ) { index, hit -> SearchHitRow(hit, query.length, index == cursor.selected) { open(hit) } }
        }
    }
}

// Title, engine (when it is not ripgrep) and count of the matches, followed by "…" while more may come.
@Composable
private fun Header(search: FindInFilesSearch, query: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Find in Files", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (search.builtIn) {
            Label(
                "Built-in search (ripgrep unavailable)",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 12.dp).testTag(Tags.FIND_IN_FILES_ENGINE),
            )
        }
        Label(
            status(search, query),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.testTag(Tags.FIND_IN_FILES_COUNT),
        )
    }
}

// Matches arrive in batches and are inserted in order: the selected one stays selected wherever it moves.
@Composable
private fun KeepSelection(list: List<SearchHit>, cursor: ListCursor) {
    val shown = remember { arrayOfNulls<List<SearchHit>>(1) }
    LaunchedEffect(list) {
        val previous = shown[0]
        val selected = previous?.getOrNull(cursor.selected)
        if (previous != null && selected != null && cursor.selected > 0) {
            cursor.selected = list.indexOf(selected).coerceAtLeast(0)
        }
        shown[0] = list
    }
}

private fun status(search: FindInFilesSearch, query: String): String {
    val hits = search.hits
    val error = search.error
    val cut = search.finished?.complete == false
    val count = if (cut) "${hits.size}+ matches" else plural(hits.size, "match", "matches")
    val summary = "$count in ${plural(search.fileCount, "file", "files")}"
    return when {
        query.isEmpty() -> ""
        error != null -> "Search failed: $error"
        search.searching && hits.isEmpty() -> "Searching…"
        search.searching -> "$summary…"
        hits.isEmpty() -> "No matches"
        else -> summary
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, length: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(ItemTags.findInFilesItem(hit.relativePath, hit.line))
            .resultRow(selected)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val trimmed = hit.lineText.trimStart()
        val shift = hit.lineText.length - trimmed.length
        BasicText(
            highlightRange(trimmed, hit.column - shift, length),
            Modifier.weight(1f),
            style = LocalCodeStyle.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Label(
            "${hit.entry.name} ${hit.line + 1}",
            Modifier.padding(start = 10.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}
