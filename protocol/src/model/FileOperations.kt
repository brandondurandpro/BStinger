package org.example.kotlineditor.model

/** A file operation of the IDE that language servers may follow, or adapt the code to (LSP 3.16 `fileOperations`). */
enum class FileOperation(
    /** Its name in the LSP methods and capabilities (`Rename` in `workspace/willRenameFiles`). */
    val lspName: String,
) {
    /** A file or a directory is created. */
    Create("Create"),

    /** A file or a directory is renamed or moved. */
    Rename("Rename"),

    /** A file or a directory is deleted. */
    Delete("Delete"),
}

/** One file or directory of an operation: its path, and its new path for a rename. */
data class OperatedFile(
    /** The path on the server machine (the old one for a rename). */
    val path: String,
    /** The new path of a rename; null otherwise. */
    val newPath: String? = null,
    /** Whether it is a directory. */
    val directory: Boolean = false,
)
