package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.syntax.Declaration

/** The project as seen by the project tree and the search popups. */
interface TreeHost : ProjectHost {
    val tree: FileTreeState
    val fileIndex: FileIndex
    val editor: EditorState

    /** Dialog opened from the tree context menu, if any. */
    var fileDialog: FileDialog?

    /** "Always Select Opened File": the active file is selected and revealed in the tree. */
    var autoSelectOpenedFile: Boolean

    /** Git status of the changed files, by path relative to the root. */
    val gitStatus: Map<String, GitFileStatus>

    /** Opens [entry] in the editor, as a click in the tree does. */
    fun openFile(entry: FileEntry)

    /** Opens [entry] with [start]..[end] selected, remembering the current position for Back. */
    fun navigate(entry: FileEntry, start: TextPos, end: TextPos = start)

    /** The declarations of [document] from its language server; null when none answers (it is outlined locally). */
    suspend fun serverOutline(document: Document): List<Declaration>? = null

    /** The declarations of the project named like [query], from its language servers; empty when none answers. */
    suspend fun serverSymbols(query: String): List<SymbolMatch> = emptyList()
}
