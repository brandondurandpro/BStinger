package org.example.kotlineditor.state

/** The project as seen by the project tree and the search popups. */
interface TreeHost : ProjectHost {
    val tree: FileTreeState
    val fileIndex: FileIndex
    val editor: EditorState

    /** Dialog opened from the tree context menu, if any. */
    var fileDialog: FileDialog?
}
