package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry

/** Dialogs opened from the project tree context menu. */
sealed interface FileDialog {
    val error: String?

    fun withError(message: String): FileDialog

    data class Create(val directory: FileEntry, val isDirectory: Boolean, override val error: String? = null) :
        FileDialog {
        override fun withError(message: String) = copy(error = message)
    }

    data class Rename(val entry: FileEntry, override val error: String? = null) : FileDialog {
        override fun withError(message: String) = copy(error = message)
    }

    /**
     * Deploys [entries] (files and directories of the project) to a folder typed in the dialog. While [running]
     * the dialog waits; once done [result] tells what was copied, and [error] why it failed otherwise.
     */
    data class Deploy(
        val entries: List<FileEntry>,
        val running: Boolean = false,
        val result: String? = null,
        val destination: String? = null,
        override val error: String? = null,
    ) : FileDialog {
        override fun withError(message: String) = copy(running = false, result = null, error = message)
    }

    data class Delete(val entry: FileEntry, override val error: String? = null) : FileDialog {
        override fun withError(message: String) = copy(error = message)
    }
}
