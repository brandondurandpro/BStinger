package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State of the in-editor find bar (Ctrl+F). */
@Stable
class FindState {
    var visible by mutableStateOf(false)
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var replaceVisible by mutableStateOf(false)
    var replacement by mutableStateOf("")
    var matches: List<TextPos> by mutableStateOf(emptyList())
        private set
    var current by mutableIntStateOf(-1)
        private set

    internal var pendingSelectNext = false

    /** Installs freshly computed [found] occurrences and makes the first one at/after [caret] current. */
    fun setMatches(found: List<TextPos>, caret: TextPos) {
        matches = found
        current = if (found.isEmpty()) -1 else found.indexOfFirst { it >= caret }.coerceAtLeast(0)
    }

    fun next(document: Document) = step(document, 1)

    fun previous(document: Document) = step(document, -1)

    /** Selects the current match in [document]. */
    fun selectCurrent(document: Document) {
        matches.getOrNull(current)?.let { document.select(it, TextPos(it.line, it.column + query.length)) }
    }

    /** Replaces the current occurrence (if it is the selection) and moves to the next one. */
    fun replaceCurrent(document: Document) {
        val match = matches.getOrNull(current)
        val selectedMatch = match != null && document.selectionRange()?.first == match
        if (selectedMatch && document.selectedText().equals(query, ignoreCase = !matchCase)) {
            document.insert(replacement)
            // Matches are recomputed from the new text; select the next one once they are.
            pendingSelectNext = true
        } else {
            selectCurrent(document)
        }
    }

    fun replaceAll(document: Document) {
        document.replaceAll(matches, query.length, replacement)
    }

    private fun step(document: Document, delta: Int) {
        if (matches.isNotEmpty()) {
            val onCurrent = current >= 0 && document.selectionRange()?.first == matches.getOrNull(current)
            current = if (onCurrent) (current + delta).mod(matches.size) else current.coerceAtLeast(0)
            selectCurrent(document)
        }
    }
}
