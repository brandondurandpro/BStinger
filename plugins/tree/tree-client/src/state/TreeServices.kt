package org.example.kotlineditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The tree of the files of a project, as [ProjectHost.service] gives it to the tree feature. */
val TREE_SERVICE = ServiceKey<FileTreeState>("tree")

/** The index of the files of a project: Goto File, symbols, and the file lists of the dialogs. */
val FILE_INDEX_SERVICE = ServiceKey<FileIndex>("file-index")

/** The file dialogs a project opens (New File, Rename, Delete, Deploy), shown by the popups of the window. */
val TREE_DIALOGS_SERVICE = ServiceKey<TreeDialogs>("tree-dialogs")

/** The dialogs of the tree of a project: the one open, if any. */
class TreeDialogs {
    var fileDialog: FileDialog? by mutableStateOf(null)
}
