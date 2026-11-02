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
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.TreeHost
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.state.selectedText
import java.io.IOException

private const val DEBOUNCE_MS = 150L

/** Find in Files (Ctrl+Shift+F): searched by the server, results shown as they arrive. */
@Composable
fun FindInFilesPopup(project: TreeHost, onClose: () -> Unit) {
    val initial =
        project.editor.activeTab
            ?.document
            ?.selectedText()
            ?.takeIf { '\n' !in it }
            .orEmpty()
    var query by remember { mutableStateOf(initial) }
    var matchCase by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchHit>?>(emptyList()) }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, matchCase) {
        if (query.isNotEmpty()) delay(DEBOUNCE_MS) // restarted on every keystroke
        results = null
        results = search(project, query, matchCase)
        cursor.selected = 0
    }
    val list = results.orEmpty()
    val open = { hit: SearchHit? ->
        hit?.let {
            onClose()
            project.editor.openAt(it.entry, TextPos(it.line, it.column), TextPos(it.line, it.column + query.length))
        }
        Unit
    }
    PopupFrame(TreeTags.FIND_IN_FILES, onClose, width = 760) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Find in Files", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Label(
                    status(results, query),
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(TreeTags.FIND_IN_FILES_COUNT),
                )
            }
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(TreeTags.FIND_IN_FILES_INPUT)
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

private suspend fun search(project: TreeHost, query: String, matchCase: Boolean): List<SearchHit> =
    try {
        project.fileIndex.findInFiles(query, ignoreCase = !matchCase)
    } catch (expected: IOException) {
        emptyList() // server unreachable: no results until it is back
    }

private fun status(results: List<SearchHit>?, query: String): String =
    when {
        results == null -> "Searching…"
        query.isEmpty() -> ""
        results.isEmpty() -> "No matches"
        else -> "${plural(results.size, "match", "matches")} in ${plural(fileCount(results), "file", "files")}"
    }

private fun fileCount(results: List<SearchHit>) = results.distinctBy { it.entry.path }.size

@Composable
private fun SearchHitRow(hit: SearchHit, length: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(TreeTags.findInFilesItem(hit.relativePath, hit.line))
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
