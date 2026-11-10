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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

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
    // The command that runs OpenSSH for the deployments to another machine.
    ssh: List<String> = listOf("ssh"),
) {
    private val languageServersLazy = lazy { LanguageServerService(languageServerSettings) { telemetry } }
    private val agentsLazy = lazy { AgentService(AgentFiles(), agentSettings) }

    /** What the server measures with (the language servers' starts), set by the server that uses these services. */
    @Volatile var telemetry: Telemetry = Telemetry.Disabled

    /** Files of the projects. */
    val fileSystem = LocalFileSystem(maxFileSize)

    /** Project-wide queries: Find in Files with ripgrep, the built-in search otherwise. */
    val search = RipgrepSearch(LocalSearch(fileSystem), ripgrep, maxFileSize)

    /** Copies files of a project to a folder, here or on another machine. */
    val deployer = LocalDeployer(ssh)

    /** The deployments saved for each project. */
    val deployProfiles = FileDeployProfiles(File(dataDirectory, "deployments"))

    /** Git information. */
    val git = CliGitClient()

    /** What changes on the disk of the projects, whoever changes it; the language servers are told too. */
    val disk = ProjectWatches { changes -> if (languageServersStarted) languageServers.diskChanged(changes) }

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

    /** Stops what runs in the background: the watches of the disk, the language servers and the agents. */
    fun close() {
        disk.close()
        if (languageServersStarted) languageServers.close()
        if (agentsLazy.isInitialized()) agents.close()
    }

    // The files of the agents are read and written as the editor does: in their charset, keeping what the file is
    // (permissions, links, attributes); the language servers are told, by the watch when it sees the file.
    private inner class AgentFiles : FileAccess {
        override fun read(path: String): String =
            try {
                TextFiles.readText(Paths.get(path))
            } catch (failure: IOException) {
                throw IOException("Cannot read $path: ${failure.message}", failure)
            }

        override fun write(path: String, text: String) {
            val file = Paths.get(path)
            val existing = if (Files.exists(file)) Files.readAllBytes(file) else null
            writeFileNow(file, TextFiles.encode(text, existing))
            val change = if (existing != null) DiskChange.Changed else DiskChange.Created
            val told = disk.reports(path)
            if (languageServersStarted && !told) languageServers.diskChanged(listOf(path to change))
        }
    }
}

/** [ServerPlaces] on this machine: its home directory. */
class LocalPlaces : ServerPlaces {
    override suspend fun home(): String? = System.getProperty("user.home")
}
