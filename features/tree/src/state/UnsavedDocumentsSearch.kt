package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import org.example.kotlineditor.model.FileEntry

/**
 * The options of a text search (Find in Files). Only case-insensitivity is shown in the popups so far; the other
 * Find in Files options have their field here already, so the searches can take them without breaking callers.
 */
@Immutable
data class SearchOptions(
    /** Whether letter case is ignored. */
    val ignoreCase: Boolean = true,
    /** Whether a match must be a whole word (between characters that are not of a word). */
    val wholeWord: Boolean = false,
    /** Whether [findInFiles]' query is a regular expression rather than a text to find literally. */
    val regex: Boolean = false,
)

/**
 * A document modified in memory, with its text at the moment of the search (never a stale copy): Find in Files
 * searches it no matter what the file on disk holds, and its hits replace the disk hits of the same file.
 */
@Immutable
data class UnsavedDocument(val entry: FileEntry, val lines: List<String>)

/** The open documents with unsaved edits (the tabs marked modified), each document once, with their current text. */
fun EditorState.unsavedDocuments(): List<UnsavedDocument> {
    val tabs = allTabs.filter { tab -> tab.document?.isModified == true }
    // The same document open in several panes is searched once, as its first tab.
    val kept = tabs.distinctBy { it.document }
    return kept.map { tab ->
        val document = checkNotNull(tab.document)
        UnsavedDocument(tab.entry, document.snapshotLines())
    }
}

// The hits of the whole displayed list, sorted by path then line.
private val BY_PATH_THEN_LINE =
    compareBy<SearchHit> { it.relativePath }.thenBy { it.line }.thenBy { it.column }

// How long a found line is kept for the display, as the built-in search bounds its preview.
private const val MAX_LINE_PREVIEW = 500

/**
 * Find in Files over the documents [unsavedDocuments] (the text the editor holds), reported like the disk
 * results of [FileIndex.findInFiles]: one hit per line (first match of a line), `SearchHit`s whose paths are
 * relative to the root.
 */
class UnsavedDocumentsSearch(private val relativePath: (FileEntry) -> String) {
    /**
     * Hits of [query] in [documents], honoring [options], sorted by path then line. Lines never match an empty
     * search, and a broken regular expression simply finds nothing (the popup shows it its own way).
     */
    fun hits(
        query: String,
        documents: List<UnsavedDocument>,
        options: SearchOptions = SearchOptions(),
    ): List<SearchHit> {
        if (query.isEmpty()) return emptyList()
        val matcher = Matcher(query, options)
        val found = documents.flatMap { document -> hitsOf(document, matcher) }
        return found.sortedWith(BY_PATH_THEN_LINE)
    }

    /**
     * [unsaved]'s hits replace the disk hits of the same files (a document is never searched twice); the whole
     * merged list stays sorted by path then line, as it is displayed.
     */
    fun merge(disk: List<SearchHit>, unsaved: List<SearchHit>): List<SearchHit> =
        (disk + unsaved).sortedWith(BY_PATH_THEN_LINE)

    // Hits in every line of [document], one per line at most.
    private fun hitsOf(document: UnsavedDocument, matcher: Matcher): List<SearchHit> {
        val entry = document.entry
        val path = relativePath(entry)
        return document.lines
            .withIndex()
            .mapNotNull { (line, text) -> hit(entry, path, line, text, matcher) }
    }

    private fun hit(entry: FileEntry, path: String, line: Int, text: String, matcher: Matcher): SearchHit? {
        val column = matcher.columnOf(text) ?: return null
        return SearchHit(entry, path, line, column, text.take(MAX_LINE_PREVIEW))
    }
}

// Whether a character can be part of the words Whole Word matches (like the editor's own word motions).
private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

// [query] as a regular expression pattern: word-bounded when Whole Word is on.
private fun asPattern(query: String, wholeWord: Boolean): String =
    if (wholeWord) {
        "\\b(?:$query)\\b"
    } else {
        query
    }

/** A query against one line: literal, whole-word or regular expression, with or without letter case. */
private class Matcher(query: String, options: SearchOptions) {
    private val ignoreCase = options.ignoreCase
    private val wholeWord = options.wholeWord

    // A literal query only, null for a regular expression one (then `regex` matches instead).
    private val literal: String? =
        if (options.regex) {
            null
        } else {
            query
        }

    // null only for a literal query; a broken regular expression matches nothing (the popup shows it its own way).
    private val regex =
        if (!options.regex) {
            null
        } else {
            runCatching {
                val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(asPattern(query, options.wholeWord), flags)
            }.getOrNull()
        }

    // Zero-based column of the first match in [line], or null when the line does not match.
    fun columnOf(line: String): Int? {
        val text = literal
        return if (text != null) {
            columnOfLiteral(text, line)
        } else {
            regex?.find(line)?.range?.first
        }
    }

    private fun columnOfLiteral(text: String, line: String): Int? =
        if (!wholeWord) {
            firstColumnOf(text, line)
            // Any occurrence must be bounded for a whole word; later occurrences are tried when one is not.
        } else {
            firstWholeWordColumnOf(text, line)
        }

    // The first occurrence, whichever characters surround it.
    private fun firstColumnOf(text: String, line: String): Int? {
        val column = line.indexOf(text, ignoreCase = ignoreCase)
        return column.takeIf { it >= 0 }
    }

    private fun firstWholeWordColumnOf(text: String, line: String): Int? {
        val columns = ArrayList<Int>()
        var from = 0
        while (from <= line.length) {
            val column = line.indexOf(text, from, ignoreCase = ignoreCase)
            if (column < 0) break
            columns += column
            from = column + 1
        }
        // The first occurrence that is between characters no word is made of.
        return columns.firstOrNull { column -> line.isWordBounded(column, text) }
    }

    private fun String.isWordBounded(column: Int, match: String): Boolean {
        val length = match.length
        val before = getOrNull(column - 1)
        val after = getOrNull(column + length)
        return before?.isWordChar() != true && after?.isWordChar() != true
    }
}
