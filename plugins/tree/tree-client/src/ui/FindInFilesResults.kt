package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.state.FindInFilesSearch
import org.example.kotlineditor.state.SearchHit

private const val STALE_ALPHA = 0.45f

/** The results of the search: what is typed below the chips while rows arrive, an error instead when it failed. */
@Composable
internal fun ResultsSection(
    search: FindInFilesSearch,
    results: List<SearchHit>,
    query: String,
    options: FindChips,
    cursor: ListCursor,
    open: (SearchHit?) -> Unit,
) {
    val error = search.error
    if (error == null) {
        // The results of the replaced search stay, dimmed, until the new ones arrive: the list never blanks.
        Box(Modifier.alpha(if (search.stale) STALE_ALPHA else 1f)) {
            ResultList(results, cursor.selected) { index, hit ->
                SearchHitRow(hit, matchLength(optionsOf(query, options), hit), index == cursor.selected) { open(hit) }
            }
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

// How much of the line's hit is part of the match: the whole query, or whatever a regular expression matched.
internal fun matchLength(options: FindOptions, hit: SearchHit): Int {
    if (!options.regex) return options.text.length
    val range = regexOf(options)?.find(hit.lineText, hit.column.coerceAtLeast(0))?.range
    return range?.let { it.last - it.first + 1 } ?: options.text.length
}

// The regular expression the built-in engine matches lines with: enclosed in word boundaries for whole words.
private fun regexOf(options: FindOptions): Regex? =
    runCatching {
        val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val pattern = if (options.wholeWord) "\\b(?:${options.text})\\b" else options.text
        Regex(pattern, flags)
    }.getOrNull()

// From the search and its progress to the line of the header: what the user knows while it runs and at its end.
internal fun status(search: FindInFilesSearch, query: String): String =
    when {
        query.isEmpty() -> ""
        search.stale -> "Searching…"
        !search.finished && search.results.isEmpty() -> "Searching…"
        !search.finished -> "${plural(search.results.size, "match", "matches")}…"
        search.results.isEmpty() -> "No matches"
        else -> "${plural(search.results.size, "match", "matches")} in ${plural(fileCount(search), "file", "files")}"
    }

private fun fileCount(search: FindInFilesSearch) = search.results.distinctBy { it.entry.path }.size

// The engine of the search is part of its results: ripgrep ran it, or the built-in one took over (ticket #13).
@Composable
internal fun engineLabel(search: FindInFilesSearch) {
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

// A row of the results: the line of the hit, its match highlighted, with its file and its line number.
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
