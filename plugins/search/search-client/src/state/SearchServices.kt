package org.example.kotlineditor.state

/** The Find in Files of a project, shared by its side panel and its popup. */
val FIND_SERVICE = ServiceKey<FindInFilesSearch>("find-in-files")

/** The project as the Find in Files panel sees it: the search, the editors, and the index of the files. */
interface FindHost : ProjectHost {
    val findInFiles: FindInFilesSearch
    val editor: EditorState
    val fileIndex: FileIndex
}

/**
 * The project as the Find in Files feature sees it: the [host], plus the states the search reaches, through
 * [ProjectHost.service] and not through the host itself.
 */
class FindProjectView(private val host: ProjectHost) : FindHost, ProjectHost by host {
    override val findInFiles: FindInFilesSearch get() = host.service(FIND_SERVICE)
    override val editor: EditorState get() = host.service(EDITOR_SERVICE)
    override val fileIndex: FileIndex get() = host.service(FILE_INDEX_SERVICE)
}
