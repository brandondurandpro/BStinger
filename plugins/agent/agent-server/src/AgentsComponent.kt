package org.example.kotlineditor.agent.server

import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.acp.FileAccess
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.TextFiles
import org.example.kotlineditor.server.writeFileNow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

// The ids of the sessions of the members of the teams, which the history does not list.
private const val HIDDEN_SESSIONS_FILE = "agent-hidden-sessions.txt"

/** The conversations with the agents of a server, started on the first prompt, and stopped with the services. */
internal class AgentsComponent(val services: LocalServices) {
    private val lazyAgents = lazy { AgentService(AgentFiles(), settingsWithData()) }

    /** The agents; the first use starts them. */
    val agents: AgentService by lazyAgents

    init {
        services.closeWith { if (lazyAgents.isInitialized()) agents.close() }
    }

    // The sessions hidden from the history are saved with the server's data, unless the settings say where.
    private fun settingsWithData(): AgentSettings {
        val settings = services.agentSettings
        if (settings.hiddenSessions != null) return settings
        val file = services.dataDirectory.toPath().resolve(HIDDEN_SESSIONS_FILE)
        return settings.copy(hiddenSessions = file)
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
            val told = services.disk.reports(path)
            if (!told) services.diskChanged(listOf(path to change))
        }
    }
}

/** The agents of [this], one for all the contributions. */
internal fun LocalServices.agentsComponent(): AgentsComponent = component("agents") { AgentsComponent(this) }
