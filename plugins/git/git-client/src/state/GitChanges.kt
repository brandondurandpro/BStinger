package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.GitFileStatus

/** The changed files of a project and the ones checked in the Git panel for the next commit. */
@Stable
internal class GitChanges {
    var status: Map<String, GitFileStatus> by mutableStateOf(emptyMap())
        private set

    var selectedForCommit: Set<String> by mutableStateOf(emptySet())
        private set

    /** The rows highlighted in the list (a click, Ctrl+click, Shift+click): what a revert in the menu acts on. */
    var picked: Set<String> by mutableStateOf(emptySet())
        private set

    // The row a Shift+click extends the selection from.
    private var anchor: String? = null

    /** Shows [changes]; files that left the status are unchecked, so the selection never points outside the list. */
    fun apply(changes: Map<String, GitFileStatus>) {
        status = changes
        selectedForCommit = selectedForCommit intersect changes.keys
        picked = picked intersect changes.keys
    }

    /**
     * Highlights the row [path] of [ordered] (the list as shown): alone for a plain click, added or removed with
     * [toggle] (Ctrl), or with every row between the last one clicked and it with [extend] (Shift).
     */
    fun pick(path: String, ordered: List<String>, toggle: Boolean, extend: Boolean) {
        val from = anchor?.let { ordered.indexOf(it) } ?: -1
        val to = ordered.indexOf(path)
        picked =
            when {
                extend && from >= 0 && to >= 0 -> ordered.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
                toggle -> if (path in picked) picked - path else picked + path
                else -> setOf(path)
            }
        if (!extend) anchor = path
    }

    /** Makes sure the row [path] is highlighted before a menu acts on the highlighted rows (a right click). */
    fun pickForMenu(path: String) {
        if (path !in picked) {
            picked = setOf(path)
            anchor = path
        }
    }

    fun toggle(path: String) {
        selectedForCommit = if (path in selectedForCommit) selectedForCommit - path else selectedForCommit + path
    }

    fun toggleAll() {
        selectedForCommit = if (selectedForCommit.size == status.size) emptySet() else status.keys
    }
}
