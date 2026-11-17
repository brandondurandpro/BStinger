package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.testkit.skills.SkillRepositories
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val MATT = "mattpocock/skills"
internal const val ANTHROPIC = "anthropics/skills"
internal const val OBRA = "obra/superpowers"
internal val CATALOG_SOURCES = listOf(MATT, ANTHROPIC, OBRA)
private const val TDD = "skills/engineering/tdd"
private const val PDF = "skills/pdf"

/** The three repositories of the catalog, each with a few skills, on a loopback server. */
internal fun SkillRepositories.addPopular() {
    add(
        MATT,
        skill(MATT, TDD, "tdd", "Red-green-refactor, one vertical slice at a time", mapOf("tests.md" to "How to test")) +
            skill(MATT, "skills/productivity/grill-me", "grill-me", "Get relentlessly interviewed about a plan") +
            mapOf("LICENSE" to "MIT License"),
    )
    add(ANTHROPIC, skill(ANTHROPIC, PDF, "pdf", "Read and fill PDF files") + mapOf("LICENSE" to "Apache License"))
    add(OBRA, skill(OBRA, "skills/brainstorming", "brainstorming", "Turn an idea into a design before any code"))
}

/**
 * The skill catalog of the Teams window: the skills of popular repositories, which the server downloads and the user
 * installs, so that the agents of a team can be given them.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSkillCatalogE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()
    private val repositories = SkillRepositories()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
        repositories.close()
    }

    private fun ComposeUiTest.launch() = launchWithProject(project, teamPreferences(), agents = servers.settings(repositories, CATALOG_SOURCES))

    @Test
    fun `the catalog lists the skills of every repository with their descriptions`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSource(MATT), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogSource(ANTHROPIC))
            awaitTag(AgentTags.agentCatalogSource(OBRA))
            assertEquals("mattpocock/skills (2)", textOf(AgentTags.agentCatalogSource(MATT)))
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD))
            assertTrue(textOf(AgentTags.agentCatalogSkill(MATT, TDD)).contains("Red-green-refactor"))
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, PDF))
            assertEquals(3, repositories.requests)
        }

    @Test
    fun `typing narrows the skills, and a filter that matches nothing says so`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_CATALOG_FILTER).performTextInput("interviewed")
            awaitNoTag(AgentTags.agentCatalogSkill(MATT, TDD))
            awaitTag(AgentTags.agentCatalogSkill(MATT, "skills/productivity/grill-me"))
            awaitNoTag(AgentTags.agentCatalogSource(ANTHROPIC))
            awaitTag(AgentTags.AGENT_CATALOG_FILTER).performTextInput(" zzz")
            awaitTag(AgentTags.AGENT_CATALOG_EMPTY)
        }

    @Test
    fun `installing a skill shows it installed, copies it to the data directory, and offers it to the teams`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_SKILL_CATALOG).performClick()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD), AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            val folder = File(app.process.dataDirectory, "skills/mattpocock-skills/engineering--tdd")
            assertEquals("How to test", File(folder, "tests.md").readText())
            assertEquals("MIT License", File(app.process.dataDirectory, "skills/mattpocock-skills/LICENSE").readText())
            assertEquals("Update", textOf(AgentTags.agentCatalogInstall(MATT, TDD)))
            // The skill is now one the agents of a team can be given.
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.teams.skills.orEmpty().any { it.name == "tdd" } }
            awaitTag(AgentTags.AGENT_CATALOG_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_CATALOG_DIALOG)
            awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
            newTeam("Skilled")
            val tdd = checkNotNull(app.project.agent.teams.skills.orEmpty().firstOrNull { it.name == "tdd" })
            awaitField(AgentTags.TEAM_MANAGER, "skills").performClick()
            awaitTag(AgentTags.agentTeamValue(AgentTags.TEAM_MANAGER, "skills", tdd.id), AGENT_TIMEOUT)
        }

    @Test
    fun `a repository that cannot be read is reported, and the others are listed`() =
        runComposeUiTest {
            repositories.addPopular()
            repositories.remove(OBRA)
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSource(MATT), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogSourceError(OBRA))
            assertTrue(textOf(AgentTags.agentCatalogSourceError(OBRA)).contains("Cannot download"))
        }

    @Test
    fun `Refresh downloads the repositories again, and the window reopens without downloading`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_CATALOG_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_CATALOG_DIALOG)
            awaitTag(AgentTags.AGENT_SKILL_CATALOG).performClick()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD))
            assertEquals(3, repositories.requests, "the second opening reads what the first listed")
            repositories.add(OBRA, repositories.skill(OBRA, "skills/debugging", "debugging", "Find the cause first"))
            awaitTag(AgentTags.AGENT_CATALOG_REFRESH).performClick()
            awaitTag(AgentTags.agentCatalogSkill(OBRA, "skills/debugging"), AGENT_TIMEOUT)
            assertEquals(6, repositories.requests)
        }

    @Test
    fun `a skill that cannot be installed says why, and stays installable`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, PDF), AGENT_TIMEOUT)
            // The copy the server kept is gone and the repository is no longer there to download again.
            File(app.process.dataDirectory, "skill-catalog/anthropics-skills").deleteRecursively()
            repositories.remove(ANTHROPIC)
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, PDF)).performClick()
            awaitTag(AgentTags.agentCatalogInstallError(ANTHROPIC, PDF), AGENT_TIMEOUT)
            assertEquals(0, onAllNodesWithTag(AgentTags.agentCatalogInstalled(ANTHROPIC, PDF)).fetchSemanticsNodes().size)
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, PDF))
            repositories.addPopular()
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, PDF)).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(ANTHROPIC, PDF), AGENT_TIMEOUT)
            awaitNoTag(AgentTags.agentCatalogInstallError(ANTHROPIC, PDF))
        }
}
