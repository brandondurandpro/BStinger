package org.example.kotlineditor.state

import kotlinx.coroutines.CoroutineScope
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry

/** The project as seen by the deployments; `ProjectState` implements it in `shared`. */
interface DeployHost : ProjectHost {
    /** The scope of the project: the deployments end with it. */
    val scope: CoroutineScope

    /** The server the deployments run on. */
    val services: AppServices

    /** The path selected in the project view; null when none. */
    val selectedPath: String?

    /** The destination of the last deployment, offered again by the next one. */
    var deployDestination: String

    /** Entry of the file at [relativePath] (paths are relative to the project root, with `/` separators). */
    fun entryAt(relativePath: String): FileEntry
}
