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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.FileIndex
import org.example.kotlineditor.state.SymbolMatch
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.TreeHost
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.Structure
import java.io.IOException

private const val SYMBOL_DEBOUNCE_MS = 100L

/** File Structure (Ctrl+F12): the declarations of the current file, filterable, Enter jumps to one. */
@Composable
fun FileStructurePopup(
    document: Document,
    onClose: () -> Unit,
    serverOutline: suspend () -> List<Declaration>? = { null },
) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<Declaration>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        val lines = document.lines.toList()
        val states = document.commentStates
        val local = withContext(Dispatchers.Default) { Structure.outline(lines, document.language, states) }
        // A language without a local outline (PHP) asks its language server.
        all = local.ifEmpty { askOutline(serverOutline) }
        loading = false
        // Start on the declaration enclosing the caret, like IntelliJ.
        cursor.selected = all.indexOfLast { it.line <= document.caret.line }.coerceAtLeast(0)
    }
    val results =
        remember(
            all,
            query,
        ) { if (query.isBlank()) all else all.filter { FileIndex.fuzzyMatch(query.trim(), it.name) != null } }
    LaunchedEffect(query) { if (query.isNotBlank()) cursor.selected = 0 }
    val open = { declaration: Declaration? ->
        declaration?.let {
            document.moveTo(TextPos(it.line, it.column))
            onClose()
        }
        Unit
    }
    PopupFrame(TreeTags.STRUCTURE, onClose, width = 520) {
        Column {
            Label("File Structure", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .testTag(TreeTags.STRUCTURE_INPUT)
                    .focusRequester(focus)
                    .popupKeys({ open(results.getOrNull(cursor.selected)) }, onClose) { cursor.move(it, results.size) },
                placeholder = "Filter declarations",
                leading = { SearchIcon() },
            )
            if (loading) {
                val modifier = Modifier.padding(8.dp).testTag(TreeTags.STRUCTURE_LOADING)
                Label("Loading…", color = IdeColors.TextSecondary, modifier = modifier)
            }
            ResultList(results, cursor.selected) { index, declaration ->
                DeclarationRow(
                    declaration,
                    indented = query.isBlank(),
                    selected = index == cursor.selected,
                ) { open(declaration) }
            }
        }
    }
}

@Composable
private fun DeclarationRow(declaration: Declaration, indented: Boolean, selected: Boolean, onClick: () -> Unit) {
    val indent = if (indented) 16.dp * declaration.depth else 0.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(TreeTags.structureItem(declaration.name, declaration.line))
            .resultRow(selected)
            .padding(start = 8.dp + indent, top = 3.dp, bottom = 3.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(declaration.kind)
        Label(declaration.name, Modifier.padding(start = 8.dp))
        Label(
            ":${declaration.line + 1}",
            Modifier.padding(start = 6.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/** Go to Symbol (Ctrl+Alt+Shift+N): fuzzy search over the declarations of the whole project. */
@Composable
fun GotoSymbolPopup(project: TreeHost, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SymbolMatch>?>(emptyList()) }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(SYMBOL_DEBOUNCE_MS)
        results = null
        results = symbols(project, query)
        cursor.selected = 0
    }
    val list = results.orEmpty()
    val open = { match: SymbolMatch? ->
        match?.let {
            onClose()
            project.navigate(it.entry, TextPos(it.declaration.line, it.declaration.column))
        }
        Unit
    }
    PopupFrame(TreeTags.GOTO_SYMBOL, onClose) {
        Column {
            Label("Symbols", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .testTag(TreeTags.GOTO_SYMBOL_INPUT)
                    .focusRequester(focus)
                    .popupKeys({ open(list.getOrNull(cursor.selected)) }, onClose) { cursor.move(it, list.size) },
                placeholder = "Type a class, function or property name",
                leading = { SearchIcon() },
            )
            if (results == null) {
                val modifier = Modifier.padding(8.dp).testTag(TreeTags.GOTO_SYMBOL_SEARCHING)
                Label("Indexing symbols…", color = IdeColors.TextSecondary, modifier = modifier)
            }
            ResultList(
                list,
                cursor.selected,
            ) { index, match -> SymbolRow(match, index == cursor.selected) { open(match) } }
        }
    }
}

// The declarations found locally (Kotlin, Java) and by the language servers (PHP), best first.
private suspend fun symbols(project: TreeHost, query: String): List<SymbolMatch> {
    val local = unlessUnreachable { project.fileIndex.searchSymbols(query) }
    val fromServers = if (query.isBlank()) emptyList() else unlessUnreachable { project.serverSymbols(query.trim()) }
    val unique = (local + fromServers).distinctBy { Triple(it.relativePath, it.declaration.line, it.declaration.name) }
    return FileIndex.rankSymbols(unique)
}

// What [search] finds; nothing while the server is unreachable, until it is back.
private suspend fun unlessUnreachable(search: suspend () -> List<SymbolMatch>): List<SymbolMatch> =
    try {
        search()
    } catch (expected: IOException) {
        emptyList()
    }

private suspend fun askOutline(serverOutline: suspend () -> List<Declaration>?): List<Declaration> =
    try {
        serverOutline().orEmpty()
    } catch (expected: IOException) {
        emptyList() // server unreachable: nothing to list
    }

@Composable
private fun SymbolRow(match: SymbolMatch, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(TreeTags.gotoSymbolItem(match.declaration.name, match.relativePath))
            .resultRow(selected)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(match.declaration.kind)
        BasicText(
            highlightIndices(match.declaration.name, match.matchedIndices),
            Modifier.padding(start = 8.dp),
            style = TextStyle(fontSize = 13.sp),
            maxLines = 1,
        )
        Label(
            "${match.relativePath}:${match.declaration.line + 1}",
            Modifier.padding(start = 10.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}
