package org.example.kotlineditor.model

import kotlinx.coroutines.awaitCancellation

/**
 * What changed on disk under a project after a [DiskCursor], whoever changed it: the IDE, a terminal, a build, another
 * editor or `git checkout`.
 */
class DiskChanges(
    /** Where the next poll starts. */
    val cursor: DiskCursor,
    /** Absolute paths of the files and directories created, changed or deleted, each once. */
    val paths: List<String>,
    /**
     * False when what changed since the cursor is not known (the watch restarted with the server, or kept too few
     * changes): anything may have changed.
     */
    val complete: Boolean,
    /**
     * The versions of the open files among [paths] (the client said which documents it holds), each computed from
     * the bytes the disk holds now; [paths] with none here (beyond the cap of an answer, or a file the disk
     * refuses) are read and decided one by one, as when no version travels at all. Empty when the client named no
     * open file, or the answer is not [complete].
     */
    val versions: Map<String, FileVersion> = emptyMap(),
)

/**
 * A position in the changes of a project: the [watch] that saw them (another one after a server restart), and its
 * [version].
 */
data class DiskCursor(val watch: Long, val version: Long) {
    /** Cursors known before any poll. */
    companion object {
        /** No watch yet: the first answer gives the current position, without changes. */
        val START = DiskCursor(0, 0)
    }
}

/** Watches the projects on the disk of the server. */
interface DiskWatch {
    /**
     * The changes under [root] after [since], once there are some, or none after a while (a long poll). A cursor of
     * another watch (the server restarted, [DiskCursor.START]) is answered at once, as not [DiskChanges.complete].
     *
     * [open] names the documents the client holds (their absolute paths): the answer carries the versions of those
     * among the changes, so the client decides without reading them again.
     */
    suspend fun changes(root: String, since: DiskCursor, open: List<String> = emptyList()): DiskChanges

    /** Nothing watches the disk. */
    object None : DiskWatch {
        override suspend fun changes(root: String, since: DiskCursor, open: List<String>): DiskChanges =
            awaitCancellation()
    }
}
