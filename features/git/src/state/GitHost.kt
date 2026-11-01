package org.example.kotlineditor.state

import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitFile

/** The project as seen by the Git UI. */
interface GitHost : ProjectHost {
    val git: GitState
    val editor: EditorState

    /** Opens the Branches tool window on the history of [ref], or of the current branch. */
    fun showHistory(ref: String? = null)

    /** Opens the diff of the working tree file at [relativePath] against HEAD. */
    fun openDiff(relativePath: String)

    /** Opens the diff of [file] in [commit]. */
    fun openCommitDiff(commit: GitCommit, file: GitCommitFile)
}
