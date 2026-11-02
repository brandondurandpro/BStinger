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

    data class Delete(val entry: FileEntry, override val error: String? = null) : FileDialog {
        override fun withError(message: String) = copy(error = message)
    }
}
