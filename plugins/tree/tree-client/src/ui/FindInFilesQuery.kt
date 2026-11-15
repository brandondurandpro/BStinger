package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchFacets
import org.example.kotlineditor.state.FindInFilesSearch
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.TreeHost

// The search waits for a pause in the typing; a change of option does not wait.
private const val DEBOUNCE_MS = 250L

/**
 * Keeps [search] in step with the query and the chips, for the popup and for the side panel alike: the facets the
 * filters offer are the server's, asked once; whatever changed, of the text or of an option, restarts the same
 * debounced search with its whole state.
 */
@Composable
internal fun DriveSearch(
    project: TreeHost,
    search: FindInFilesSearch,
    query: String,
    options: FindChips,
    cursor: ListCursor,
) {
    val searched = remember { SearchedQuery() }
    LaunchedEffect(Unit) { options.facets = runCatching { project.fileIndex.facets() }.getOrDefault(options.facets) }
    LaunchedEffect(
        query,
        options.matchCase,
        options.wholeWord,
        options.regex,
        options.globText,
        options.includeIgnored,
        options.scopes,
    ) {
        val typing = query != searched.text
        if (typing && query.isNotEmpty()) delay(DEBOUNCE_MS) // restarted on every keystroke
        searched.text = query
        search.start(optionsOf(query, options))
        cursor.selected = 0
    }
}

// The field of the query, focused at once and at every new request (Ctrl+Shift+F), and driven by the cursor of the
// results with the keyboard.
@Composable
internal fun FindField(
    query: String,
    onQuery: (String) -> Unit,
    options: FindChips,
    results: List<SearchHit>,
    cursor: ListCursor,
    open: (SearchHit?) -> Unit,
    onClose: () -> Unit,
    focusRequests: Int,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) { focus.requestFocus() }
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
        trailing = {
            if (query.isNotEmpty()) ClearButton(TreeTags.FIND_IN_FILES_CLEAR) { onQuery("") }
            QueryToggles(options)
        },
    )
}

/** The options the popup's chips say, glob field and non-root scope only when they are on. */
internal fun optionsOf(query: String, options: FindChips): FindOptions =
    FindOptions(
        text = query,
        ignoreCase = !options.matchCase,
        wholeWord = options.wholeWord,
        regex = options.regex,
        // Closing the glob panel only hides it: what was chosen there keeps filtering.
        filePattern = options.globText.takeIf { it.isNotBlank() },
        scopes = options.scopes,
        includeIgnored = options.includeIgnored,
    )

/** The chips of the options row of the popup: what they toggle, and what their fields and menu show. */
@Stable
internal class FindChips {
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var globOpen by mutableStateOf(false)
    var globText by mutableStateOf("")
    var includeIgnored by mutableStateOf(false)
    var scopes by mutableStateOf(emptyList<String>())

    /** The files whose hits are folded in the results, by relative path. */
    val collapsed = mutableStateListOf<String>()
    var scopeMenuOpen by mutableStateOf(false)
    var scopeFilter by mutableStateOf("")
    var facets by mutableStateOf(SearchFacets())
}

/** The text of the last search that started: tells typing (debounced) from a change of option (immediate). */
private class SearchedQuery {
    var text: String? = null
}
