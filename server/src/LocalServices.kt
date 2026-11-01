package org.example.kotlineditor.server

import org.example.kotlineditor.model.ServerPlaces
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.File

/**
 * Everything the server does on its machine. [dataDirectory] holds recent projects and sessions; [ripgrep] says
 * where Find in Files gets its ripgrep executable.
 */
class LocalServices(
    dataDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor"),
    maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ripgrep: RipgrepSettings = RipgrepSettings(),
) {
    /** Files of the projects. */
    val fileSystem = LocalFileSystem(maxFileSize)

    /** Project-wide queries: Find in Files with ripgrep, the built-in search otherwise. */
    val search = RipgrepSearch(LocalSearch(fileSystem), ripgrep, maxFileSize)

    /** Git information. */
    val git = CliGitClient()

    /** Recently opened projects. */
    val recentProjects = FileRecentProjectsStore(File(dataDirectory, "recent-projects.txt"))

    /** Open files of each project. */
    val sessions = FileSessionStore(File(dataDirectory, "sessions"))

    /** Places of this machine, for the client's Open Project dialog. */
    val places = LocalPlaces()
}

/** [ServerPlaces] on this machine: its home directory. */
class LocalPlaces : ServerPlaces {
    override suspend fun home(): String? = System.getProperty("user.home")
}
