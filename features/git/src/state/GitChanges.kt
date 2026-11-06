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

    /** Shows [changes]; files that left the status are unchecked, so the selection never points outside the list. */
    fun apply(changes: Map<String, GitFileStatus>) {
        status = changes
        selectedForCommit = selectedForCommit intersect changes.keys
    }

    fun toggle(path: String) {
        selectedForCommit = if (path in selectedForCommit) selectedForCommit - path else selectedForCommit + path
    }

    fun toggleAll() {
        selectedForCommit = if (selectedForCommit.size == status.size) emptySet() else status.keys
    }
}
