package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.state.FindInFilesSearch
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.TreeHost
import org.example.kotlineditor.state.selectedText

private const val DEBOUNCE_MS = 150L

/** Find in Files (Ctrl+Shift+F): searched by the server, its results arriving in batches, as they are found. */
@Composable
fun FindInFilesPopup(project: TreeHost, onClose: () -> Unit) {
    val initial =
        project.editor.activeTab
            ?.document
            ?.selectedText()
            ?.takeIf { '\n' !in it }
            .orEmpty()
    val search = project.findInFiles
    var query by remember { mutableStateOf(initial) }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var globOn by remember { mutableStateOf(false) }
    var globText by remember { mutableStateOf("") }
    var includeIgnored by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(".") }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Whatever changed, of the text or of an option: the same debounced search, restarted with its whole state.
    LaunchedEffect(query, matchCase, wholeWord, regex, globOn, globText, includeIgnored, scope) {
        if (query.isNotEmpty()) delay(DEBOUNCE_MS) // restarted on every keystroke, like on every option change
        project.findInFiles.start(optionsOf(query, matchCase, wholeWord, regex, globOn, globText, includeIgnored, scope))
        cursor.selected = 0
    }
    val open = { hit: SearchHit? ->
        hit?.let {
            onClose()
            val length = matchLength(optionsOf(query, matchCase, wholeWord, regex, globOn, globText, includeIgnored, scope), it)
            val start = TextPos(it.line, it.column)
            project.editor.openAt(it.entry, start, TextPos(it.line, it.column + length))
        }
        Unit
    }
    val list = search.results
    PopupFrame(TreeTags.FIND_IN_FILES, onClose, width = 760) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Find in Files", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Label(
                    status(search, query),
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(TreeTags.FIND_IN_FILES_COUNT),
                )
                engineLabel(search)
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
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                chip(TreeTags.FIND_IN_FILES_CASE, "Cc", matchCase) { matchCase = !matchCase }
                chip(TreeTags.FIND_IN_FILES_WHOLE_WORD, "W", wholeWord) { wholeWord = !wholeWord }
                chip(TreeTags.FIND_IN_FILES_REGEX, ".*", regex) { regex = !regex }
                chip(TreeTags.FIND_IN_FILES_GLOB_BUTTON, "*", globOn) { globOn = !globOn }
                chip(TreeTags.FIND_IN_FILES_INCLUDE_IGNORED, "◎", includeIgnored) {
                    includeIgnored = !includeIgnored
                }
                scopeChip(scope) { scopeMenuOpen = !scopeMenuOpen }
            }
            if (globOn) {
                IdeTextField(
                    globText,
                    { globText = it },
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag(TreeTags.FIND_IN_FILES_GLOB_INPUT),
                    placeholder = "*.kt",
                )
            }
            if (scopeMenuOpen) {
                Column(Modifier.fillMaxWidth().testTag(TreeTags.FIND_IN_FILES_SCOPE_MENU)) {
                    for (choice in scopeChoices(project)) {
                        scopeRow(choice) {
                            scope = choice
                            scopeMenuOpen = false
                        }
                    }
                }
            }
            val error = search.error
            if (error == null) {
                ResultList(
                    list,
                    cursor.selected,
                ) { index, hit ->
                    val options = optionsOf(query, matchCase, wholeWord, regex, globOn, globText, includeIgnored, scope)
                    SearchHitRow(hit, matchLength(options, hit), index == cursor.selected) { open(hit) }
                }
            } else {
                Label(
                    error,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag(TreeTags.FIND_IN_FILES_ERROR),
                    color = IdeColors.Error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The options the popup's state says, glob field and non-root scope only when they are on. */
private fun optionsOf(
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    regex: Boolean,
    globOn: Boolean,
    globText: String,
    includeIgnored: Boolean,
    scope: String,
): FindOptions =
    FindOptions(
        text = query,
        ignoreCase = !matchCase,
        wholeWord = wholeWord,
        regex = regex,
        filePattern = globText.takeIf { globOn && it.isNotBlank() },
        scope = scope.takeIf { it != "." },
        includeIgnored = includeIgnored,
    )

// How much of the line's hit is part of the match: the whole query, or whatever a regular expression matched.
private fun matchLength(options: FindOptions, hit: SearchHit): Int {
    if (!options.regex) return options.text.length
    val regex = regexOf(options) ?: return options.text.length
    val match = regex.find(hit.lineText, hit.column.coerceAtLeast(0))
    return match?.range?.let { it.last - it.first + 1 } ?: options.text.length
}

// The regular expression the built-in engine matches lines with: enclosed in word boundaries for whole words.
private fun regexOf(options: FindOptions): Regex? =
    runCatching {
        val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val pattern = if (options.wholeWord) "\\b(?:${options.text})\\b" else options.text
        Regex(pattern, flags)
    }.getOrNull()

// From the search and its progress to the line of the header: what the user knows while it runs and at its end.
private fun status(search: FindInFilesSearch, query: String): String =
    when {
        query.isEmpty() -> ""
        !search.finished && search.results.isEmpty() -> "Searching…"
        !search.finished -> "${plural(search.results.size, "match", "matches")}…"
        search.results.isEmpty() -> "No matches"
        else -> "${plural(search.results.size, "match", "matches")} in ${plural(fileCount(search), "file", "files")}"
    }

private fun fileCount(search: FindInFilesSearch) = search.results.distinctBy { it.entry.path }.size

// The engine of the search is part of its results: ripgrep ran it, or the built-in one took over (ticket #13).
@Composable
private fun engineLabel(search: FindInFilesSearch) {
    if (!search.finished || search.error != null) return
    val engine = search.engine ?: return
    Label(
        when (engine) {
            SearchEngine.Ripgrep -> "by ripgrep"
            SearchEngine.BuiltIn -> "by the built-in engine"
        },
        Modifier
            .padding(start = 10.dp)
            .testTag(TreeTags.FIND_IN_FILES_ENGINE),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
}

// An option of the options row: a ToggleChip, tagged like the tests click it.
@Composable
private fun chip(tag: String, text: String, on: Boolean, onToggle: () -> Unit) {
    Box(Modifier.testTag(tag)) { ToggleChip(text, on, onToggle) }
}

// The scope chip: what is searched now (`.` for the whole root), opening the dropdown of the directory choices.
@Composable
private fun scopeChip(scope: String, onOpen: () -> Unit) {
    Box(
        Modifier
            .testTag(TreeTags.FIND_IN_FILES_SCOPE_BUTTON)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(scope, fontSize = 11.sp, color = IdeColors.TextSecondary)
    }
}

// A scope of the dropdown: the root (shown as "the whole project"), or one of the top-level directories.
@Composable
private fun scopeRow(choice: String, onChoose: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onChoose)
            .testTag(TreeTags.findInFilesScopeRow(choice))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Label(if (choice == ".") "the whole project" else choice, fontSize = 12.sp)
    }
}

// The choices of a scope: the root first, then the top-level directories of the project (the index knows them).
private fun scopeChoices(project: TreeHost): List<String> {
    val directories =
        project.fileIndex.files.orEmpty().mapNotNull { entry ->
            val relative = project.fileIndex.relativePath(entry)
            val top = relative.substringBefore('/', missingDelimiterValue = "")
            top.takeIf { it.isNotEmpty() }
        }.distinct().sortedBy { it.lowercase() }
    return listOf(".") + directories
}

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
