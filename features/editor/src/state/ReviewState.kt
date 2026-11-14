package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.FileEntry

/** A file of a review: [relativePath] identifies it, [entry] opens it. */
data class ReviewFile(val entry: FileEntry, val relativePath: String)

/** How a review is led. A review by an agent will be another mode of the same tab. */
enum class ReviewMode(val tabKey: String) {
    Manual("review:manual"),
}

/**
 * A review tab: the files to review, which of them the reviewer found OK (checked) and the one whose diff is shown.
 * A file that is not checked is not OK yet.
 */
@Stable
class ReviewState(val mode: ReviewMode = ReviewMode.Manual) {
    val files = mutableStateListOf<ReviewFile>()

    private val approved = mutableStateMapOf<String, Boolean>()

    /** Relative path of the file shown on the right, null when the review has no file. */
    var selected: String? by mutableStateOf(null)
        private set

    val selectedFile: ReviewFile? get() = files.firstOrNull { it.relativePath == selected }

    val approvedCount: Int get() = files.count { isApproved(it.relativePath) }

    fun isApproved(relativePath: String): Boolean = approved[relativePath] == true

    fun toggle(relativePath: String) {
        approved[relativePath] = !isApproved(relativePath)
    }

    fun select(relativePath: String) {
        if (files.any { it.relativePath == relativePath }) selected = relativePath
    }

    /**
     * Replaces the files by [updated] (the review is opened again while changes came or went): the verdicts of the
     * files that stay are kept, and so is the selection when its file stays, else the first file is shown.
     */
    fun update(updated: List<ReviewFile>) {
        val kept = updated.map { it.relativePath }.toSet()
        approved.keys.retainAll(kept)
        files.clear()
        files.addAll(updated)
        val stillThere = selected in kept
        if (!stillThere) selected = updated.firstOrNull()?.relativePath
    }
}
