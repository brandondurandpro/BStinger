package org.example.kotlineditor.server

import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.acp.FileAccess
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.model.ServerPlaces
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import org.example.kotlineditor.telemetry.Telemetry
import java.io.File

/**
 * Everything the server does on its machine. [dataDirectory] holds recent projects and sessions; [ripgrep] says
 * where Find in Files gets its ripgrep executable.
 */
class LocalServices(
    dataDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor"),
    maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ripgrep: RipgrepSettings = RipgrepSettings(),
    languageServerSettings: LanguageServerSettings = LanguageServerSettings(dataDirectory.toPath()),
    agentSettings: AgentSettings = AgentSettings(),
) {
    private val languageServersLazy = lazy { LanguageServerService(languageServerSettings) { telemetry } }
    private val agentsLazy = lazy { AgentService(AgentFiles(), agentSettings) }

    /** What the server measures with (the language servers' starts), set by the server that uses these services. */
    @Volatile var telemetry: Telemetry = Telemetry.Disabled

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

    /** Language servers, started on the first request that needs them. */
    val languageServers: LanguageServerService by languageServersLazy

    /** Whether [languageServers] was ever used (closing it otherwise would start it). */
    val languageServersStarted: Boolean get() = languageServersLazy.isInitialized()

    /** Conversations with the agents of agent mode, started on the first prompt. */
    val agents: AgentService by agentsLazy

    /** Stops what runs in the background: the language servers and the agents. */
    fun close() {
        if (languageServersStarted) languageServers.close()
        if (agentsLazy.isInitialized()) agents.close()
    }

    // The files the agents write go to the disk, and the language servers are told, as for the IDE's own writes.
    private inner class AgentFiles : FileAccess {
        override fun read(path: String): String = FileAccess.Disk.read(path)

        override fun write(path: String, text: String) {
            val existed = java.io.File(path).exists()
            FileAccess.Disk.write(path, text)
            val change = if (existed) DiskChange.Changed else DiskChange.Created
            if (languageServersStarted) languageServers.diskChanged(listOf(path to change))
        }
    }
}

/** [ServerPlaces] on this machine: its home directory. */
class LocalPlaces : ServerPlaces {
    override suspend fun home(): String? = System.getProperty("user.home")
}
