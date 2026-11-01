package org.example.kotlineditor.server

import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.File

/**
 * Everything the server does on its machine. [dataDirectory] holds recent projects and sessions; [ripgrep] says
 * where Find in Files gets its ripgrep executable; [wrapSearch] lets tests observe or slow down the searches the
 * clients run.
 */
class LocalServices(
    dataDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor"),
    maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ripgrep: RipgrepSettings = RipgrepSettings(),
    wrapSearch: (ProjectSearch) -> ProjectSearch = { it },
) {
    /** Files of the projects. */
    val fileSystem = LocalFileSystem(maxFileSize)

    /** Project-wide queries: Find in Files with ripgrep, the built-in search otherwise. */
    val search = RipgrepSearch(LocalSearch(fileSystem), ripgrep, maxFileSize)

    /** The project-wide queries the clients' requests run: [search], unless a test wrapped it. */
    val servedSearch: ProjectSearch = wrapSearch(search)

    /** Git information. */
    val git = CliGitClient()

    /** Recently opened projects. */
    val recentProjects = FileRecentProjectsStore(File(dataDirectory, "recent-projects.txt"))

    /** Open files of each project. */
    val sessions = FileSessionStore(File(dataDirectory, "sessions"))
}
