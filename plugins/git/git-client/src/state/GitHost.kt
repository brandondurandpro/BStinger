package org.example.kotlineditor.state

/** The project as seen by the Git UI: the state Git keeps, and the editor it opens files in. */
interface GitHost : ProjectHost {
    val git: GitState
    val editor: EditorState
}
