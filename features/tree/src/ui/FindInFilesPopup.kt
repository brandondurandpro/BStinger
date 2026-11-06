package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.state.FindInFilesSearch
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.TreeHost
import org.example.kotlineditor.state.selectedText

private const val DEBOUNCE_MS = 150L

/** Find in Files (Ctrl+Shift+F): searched by the server, its results arriving in batches, as they are found. */
@Composable
fun FindInFilesPopup(project: TreeHost, search: FindInFilesSearch, onClose: () -> Unit) {
    val initial =
        project.editor.activeTab
            ?.document
            ?.selectedText()
            ?.takeIf { '\n' !in it }
            .orEmpty()
    val options = remember { FindChips() }
    var query by remember { mutableStateOf(initial) }
    val cursor = remember { ListCursor() }
    // Whatever changed, of the text or of an option: the same debounced search, restarted with its whole state.
    LaunchedEffect(
        query,
        options.matchCase,
        options.wholeWord,
        options.regex,
        options.globOn,
        options.globText,
        options.includeIgnored,
        options.scope,
    ) {
        if (query.isNotEmpty()) delay(DEBOUNCE_MS) // restarted on every keystroke, like on every option change
        search.start(optionsOf(query, options))
        cursor.selected = 0
    }
    val open = { hit: SearchHit? ->
        hit?.let {
            onClose()
            val start = TextPos(it.line, it.column)
            val end = TextPos(it.line, it.column + matchLength(optionsOf(query, options), it))
            project.editor.openAt(it.entry, start, end)
        }
        Unit
    }
    val results = search.results
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
            FindField(query, { query = it }, results, cursor, open, onClose)
            OptionsSection(project, options)
            ResultsSection(search, results, query, options, cursor, open)
        }
    }
}

// The field of the query, focused at once and driven by the cursor of the results with the keyboard.
@Composable
private fun FindField(
    query: String,
    onQuery: (String) -> Unit,
    results: List<SearchHit>,
    cursor: ListCursor,
    open: (SearchHit?) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    IdeTextField(
        query,
        onQuery,
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TreeTags.FIND_IN_FILES_INPUT)
            .focusRequester(focus)
            .popupKeys({ open(results.getOrNull(cursor.selected)) }, onClose) { cursor.move(it, results.size) },
        placeholder = "Text to find",
        leading = { SearchIcon() },
    )
}

/** The options the popup's chips say, glob field and non-root scope only when they are on. */
internal fun optionsOf(query: String, options: FindChips): FindOptions =
    FindOptions(
        text = query,
        ignoreCase = !options.matchCase,
        wholeWord = options.wholeWord,
        regex = options.regex,
        filePattern = options.globText.takeIf { options.globOn && it.isNotBlank() },
        scope = options.scope.takeIf { it != "." },
        includeIgnored = options.includeIgnored,
    )

/** The chips of the options row of the popup: what they toggle, and what their fields and menu show. */
@Stable
internal class FindChips {
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var globOn by mutableStateOf(false)
    var globText by mutableStateOf("")
    var includeIgnored by mutableStateOf(false)
    var scope by mutableStateOf(".")
    var scopeMenuOpen by mutableStateOf(false)
}
