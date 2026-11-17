package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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

private const val TDD = "skills/engineering/tdd"
private const val GRILL = "skills/productivity/grill-me"
private const val PDF = "skills/pdf"

/** The skills told apart by owner, in the catalog and in the menu of an agent: a header per owner, which folds its skills away. */
@OptIn(ExperimentalTestApi::class)
class AgentSkillOwnersE2eTest {
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
    fun `a header folds the skills of its repository away and shows them again, the others stay`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogSkill(ANTHROPIC, PDF))
            awaitTag(AgentTags.agentCatalogSourceToggle(MATT)).performClick()
            awaitNoTag(AgentTags.agentCatalogSkill(MATT, TDD))
            awaitNoTag(AgentTags.agentCatalogSkill(MATT, GRILL))
            awaitTag(AgentTags.agentCatalogSkill(ANTHROPIC, PDF))
            assertEquals("mattpocock/skills (2)", textOf(AgentTags.agentCatalogSource(MATT)), "a folded header still says how many skills it holds")
            awaitTag(AgentTags.agentCatalogSourceToggle(MATT)).performClick()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD))
            awaitTag(AgentTags.agentCatalogSkill(MATT, GRILL))
        }

    @Test
    fun `every repository folds on its own`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogSourceToggle(ANTHROPIC)).performClick()
            awaitTag(AgentTags.agentCatalogSourceToggle(OBRA)).performClick()
            awaitNoTag(AgentTags.agentCatalogSkill(ANTHROPIC, PDF))
            awaitNoTag(AgentTags.agentCatalogSkill(OBRA, "skills/brainstorming"))
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD))
            awaitTag(AgentTags.agentCatalogSourceToggle(ANTHROPIC)).performClick()
            awaitTag(AgentTags.agentCatalogSkill(ANTHROPIC, PDF))
            awaitNoTag(AgentTags.agentCatalogSkill(OBRA, "skills/brainstorming"))
        }

    @Test
    fun `a filter shows the skills it matches even in a folded repository`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogSourceToggle(MATT)).performClick()
            awaitNoTag(AgentTags.agentCatalogSkill(MATT, GRILL))
            awaitTag(AgentTags.AGENT_CATALOG_FILTER).performTextInput("interviewed")
            awaitTag(AgentTags.agentCatalogSkill(MATT, GRILL))
        }

    @Test
    fun `the list of the catalog has a scrollbar at its right`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            val list = boundsOf(AgentTags.AGENT_CATALOG_LIST)
            val bar = boundsOf(AgentTags.AGENT_CATALOG_SCROLLBAR)
            assertTrue(bar.right >= list.right - 1, "the scrollbar is at the right edge of the list")
            assertTrue(bar.left > list.left + list.width / 2, "the scrollbar is at the right, not over the skills")
            val install = boundsOf(AgentTags.agentCatalogInstall(MATT, TDD))
            assertTrue(install.right <= bar.left, "the scrollbar does not hide the Install button")
        }

    @Test
    fun `the menu of the skills of an agent groups them by owner, and folds an owner away`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            // A skill put in the data directory by hand has no repository: it is the IDE's.
            val mine = File(app.process.dataDirectory, "skills/mine")
            mine.mkdirs()
            File(mine, "SKILL.md").writeText("---\nname: mine\ndescription: Made by hand\n---\n")
            openTeams()
            awaitTag(AgentTags.AGENT_SKILL_CATALOG).performClick()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD), AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_CATALOG_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_CATALOG_DIALOG)
            newTeam("Owners")
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.teams.skills.orEmpty().size == 2 }
            val skills = app.project.agent.teams.skills.orEmpty()
            val tdd = checkNotNull(skills.firstOrNull { it.name == "tdd" })
            val own = checkNotNull(skills.firstOrNull { it.name == "mine" })
            assertEquals(MATT, tdd.owner)
            assertEquals("", own.owner)
            val role = AgentTags.TEAM_MANAGER
            awaitField(role, "skills").performClick()
            awaitTag(AgentTags.agentSkillOwner(role, MATT), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentSkillOwner(role, "IDE"))
            assertEquals("$MATT (1)", textOf(AgentTags.agentSkillOwner(role, MATT)))
            awaitTag(AgentTags.agentTeamValue(role, "skills", tdd.id))
            awaitTag(AgentTags.agentTeamValue(role, "skills", own.id))
            awaitTag(AgentTags.agentSkillsScrollbar(role))
            // A checked skill is counted in its header, and still checked once its owner is folded.
            awaitTag(AgentTags.agentTeamValue(role, "skills", tdd.id)).performClick()
            awaitCondition { textOf(AgentTags.agentSkillOwner(role, MATT)) == "$MATT (1, 1 checked)" }
            awaitTag(AgentTags.agentSkillOwnerToggle(role, MATT)).performClick()
            awaitNoTag(AgentTags.agentTeamValue(role, "skills", tdd.id))
            awaitTag(AgentTags.agentTeamValue(role, "skills", own.id))
            assertEquals("Skills (1) ▾", textOf(AgentTags.agentTeamField(role, "skills")))
        }
}
