package org.example.kotlineditor.state

/**
 * The project as the tree feature sees it: the [host], plus the states the tree keeps in the project, reached
 * through [ProjectHost.service] and not through the host itself. The panels of the tree take it where the
 * contributions of the plugin receive a bare [ProjectHost].
 */
class TreeProjectView(private val host: ProjectHost) :
    TreeHost,
    ProjectHost by host {
    override val tree: FileTreeState get() = host.service(TREE_SERVICE)
    override val fileIndex: FileIndex get() = host.service(FILE_INDEX_SERVICE)
    override val editor: EditorState get() = host.service(EDITOR_SERVICE)

    override var fileDialog: FileDialog?
        get() = host.service(TREE_DIALOGS_SERVICE).fileDialog
        set(value) {
            host.service(TREE_DIALOGS_SERVICE).fileDialog = value
        }
}
