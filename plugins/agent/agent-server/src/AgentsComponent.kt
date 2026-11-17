package org.example.kotlineditor.agent.server

import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.FileAccess
import org.example.kotlineditor.harness.SkillCatalog
import org.example.kotlineditor.harness.SkillDiscovery
import org.example.kotlineditor.harness.TeamHarness
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.TextFiles
import org.example.kotlineditor.server.writeFileNow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The conversations with the agents of a server, and the teams that coordinate some of them, started on the first
 * prompt and stopped with the services.
 */
internal class AgentsComponent(val services: LocalServices) {
    private val lazyAgents = lazy { AgentService(AgentFiles(), services.agentSettings) }

    /** The agents; the first use starts them. */
    val agents: AgentService by lazyAgents

    /** The skills agents of a team can be given: the project's and those of the data directory of the server. */
    val skills = SkillDiscovery(services.dataDirectory)

    /** The popular repositories of skills, to install in the data directory of the server. */
    val skillCatalog = SkillCatalog(services.dataDirectory, services.agentSettings.skillSources, services.agentSettings.skillArchiveUrl)

    private val lazyHarness = lazy { TeamHarness(agents, skills) }

    /** The coordination of the teams, on top of [agents]; the first use starts it. */
    val harness: TeamHarness by lazyHarness

    init {
        services.closeWith {
            // The runs of the teams end before the conversations they hold.
            if (lazyHarness.isInitialized()) harness.close()
            if (lazyAgents.isInitialized()) agents.close()
        }
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
