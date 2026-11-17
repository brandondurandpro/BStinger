package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry

/**
 * Opens the review tab, led by hand, of the files [conversation] changed where it works: each file has an OK box and
 * its diff. Nothing is sent to an agent.
 */
fun AgentState.reviewManually(conversation: Conversation) {
    val workspace = conversation.workspace ?: return
    val root = workspace.root.path.trimEnd('/')
    val files =
        conversation.changes.files.map { changed ->
            ReviewFile(FileEntry(path = "$root/${changed.path}", name = changed.name, isDirectory = false), changed.path)
        }
    workspace.editor.openReview(files)
    conversation.showsChat = false
}
