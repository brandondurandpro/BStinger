package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.DEFAULT_AGENT
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.STARTER_TEAM_NAME
import org.example.kotlineditor.state.SavedTeam
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A point of the window inside its corner, outside any menu.
private const val CORNER = 6f

// The share of the app window the Teams window takes.
private const val WINDOW_SHARE = 0.9f

/**
 * The Teams button of agent mode opens a window that manages the teams of agents: a manager and members, each with
 * its provider, model, effort, skills and system prompt; a team is created, edited, duplicated and deleted, kept for
 * every project or for this one, and found again after a restart.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
    }

    private fun teamsOf(app: E2eApp): List<SavedTeam> = app.project.agent.teams.teams.toList()

    private fun onlyTeam(app: E2eApp): SavedTeam = checkNotNull(teamsOf(app).firstOrNull()) { "No team saved" }

    @Test
    fun `a team with a manager and two members is created and saved`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAMS_EMPTY)
            newTeam("Backend")
            fillAgent(AgentTags.TEAM_MANAGER, "name", "Boss")
            fillAgent(AgentTags.TEAM_MANAGER, "description", "Coordinates the work")
            fillAgent(AgentTags.TEAM_MANAGER, "prompt", "Split the task")
            fillAgent(AgentTags.teamMember(0), "name", "Coder")
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performScrollTo().performClick()
            fillAgent(AgentTags.teamMember(1), "name", "Tester")
            fillAgent(AgentTags.teamMember(1), "description", "Writes the tests")
            saveTeam()
            awaitCondition { teamsOf(app).size == 1 }
            val saved = onlyTeam(app)
            assertTrue(saved.global)
            assertEquals("Backend", saved.team.name)
            assertEquals("Boss", saved.team.manager.name)
            assertEquals("Coordinates the work", saved.team.manager.description)
            assertEquals("Split the task", saved.team.manager.systemPrompt)
            assertEquals(listOf("Coder", "Tester"), saved.team.members.map { it.name })
            assertEquals("Writes the tests", saved.team.members.getOrNull(1)?.description)
            assertEquals(TEAM_PROVIDER, saved.team.manager.agent.registry)
            assertEquals(TEAM_PROVIDER, saved.team.members.firstOrNull()?.agent?.registry)
            awaitTag(AgentTags.agentTeam(saved.team.id))
        }

    @Test
    fun `the window takes nine tenths of the app window, in width and in height`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            val window = onAllNodes(isRoot())[0].fetchSemanticsNode().size
            val dialog = awaitTag(AgentTags.AGENT_TEAMS_DIALOG).fetchSemanticsNode().size
            assertEquals(window.width * WINDOW_SHARE, dialog.width.toFloat(), absoluteTolerance = 1f)
            assertEquals(window.height * WINDOW_SHARE, dialog.height.toFloat(), absoluteTolerance = 1f)
        }

    @Test
    fun `Add Haiku team adds a ready-made team with a manager and five members`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_STARTER).performClick()
            awaitCondition(AGENT_TIMEOUT) { teamsOf(app).size == 1 }
            val saved = onlyTeam(app)
            assertTrue(saved.global)
            assertEquals(STARTER_TEAM_NAME, saved.team.name)
            assertEquals("Manager", saved.team.manager.name)
            assertEquals(listOf("Dev", "Designer", "Tester", "Code reviewer", "Spec writer"), saved.team.members.map { it.name })
            val providers = (saved.team.members + saved.team.manager).map { it.agent.registry }.toSet()
            // On Claude, whatever agent the settings choose (here the fake agent).
            assertEquals(setOf<String?>(DEFAULT_AGENT), providers)
            // The fake agent offers no Haiku model: the team keeps the default one.
            assertEquals(emptyMap(), saved.team.manager.settings)
            awaitTag(AgentTags.agentTeam(saved.team.id))
        }

    @Test
    fun `the agents of a team are tabs, one shown at a time, named after the agent`() =
        runComposeUiTest {
            val team = sampleTeam("Backend", members = 2)
            val app = launchWithProject(project, teamPreferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamEdit(team.id)).performClick()
            val manager = AgentTags.TEAM_MANAGER
            val second = AgentTags.teamMember(1)
            // The manager shows first, and only it.
            awaitTag(AgentTags.agentTeamField(manager, "name"))
            awaitNoTag(AgentTags.agentTeamField(second, "name"))
            assertEquals("Lead", textOf(AgentTags.agentTeamTab(manager)))
            assertEquals("Member 2", textOf(AgentTags.agentTeamTab(second)))
            showAgent(second)
            awaitTag(AgentTags.agentTeamField(second, "name"))
            awaitNoTag(AgentTags.agentTeamField(manager, "name"))
            // A tab follows the name typed; a new member gets a tab and shows at once.
            fillAgent(second, "name", "Reviewer")
            awaitCondition { textOf(AgentTags.agentTeamTab(second)) == "Reviewer" }
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performScrollTo().performClick()
            val third = AgentTags.teamMember(2)
            awaitTag(AgentTags.agentTeamField(third, "name"))
            awaitNoTag(AgentTags.agentTeamField(second, "name"))
            // Removing the member shown shows the one before it.
            awaitTag(AgentTags.agentTeamField(third, "remove")).performClick()
            awaitNoTag(AgentTags.agentTeamTab(third))
            awaitTag(AgentTags.agentTeamField(second, "name"))
            assertEquals(2, app.project.agent.teams.teams.firstOrNull()?.team?.members?.size)
        }

    @Test
    fun `a team is edited in place, and keeps its id`() =
        runComposeUiTest {
            val team = sampleTeam("Backend", members = 2)
            val app = launchWithProject(project, teamPreferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamEdit(team.id)).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performScrollTo()
            fillAgent(AgentTags.TEAM_MANAGER, "name", "Chief")
            awaitTag(AgentTags.AGENT_TEAM_PROJECT).performClick()
            awaitField(AgentTags.teamMember(1), "remove").performClick()
            saveTeam()
            awaitCondition { onlyTeam(app).team.manager.name == "Chief" }
            val saved = onlyTeam(app)
            assertEquals(1, teamsOf(app).size)
            assertEquals(team.id, saved.team.id)
            assertEquals(team.manager.id, saved.team.manager.id)
            assertFalse(saved.global)
            assertEquals(listOf("Member 1"), saved.team.members.map { it.name })
        }

    @Test
    fun `a team is duplicated as its name with copy, with new ids`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, teamPreferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamDuplicate(team.id)).performClick()
            awaitCondition { teamsOf(app).size == 2 }
            val copy = checkNotNull(teamsOf(app).lastOrNull()).team
            assertEquals("Backend copy", copy.name)
            assertTrue(copy.id != team.id && copy.manager.id != team.manager.id)
            assertEquals(team.members.map { it.name }, copy.members.map { it.name })
            awaitTag(AgentTags.agentTeam(copy.id))
        }

    @Test
    fun `a team is deleted after a confirmation, and kept without one`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val other = sampleTeam("Frontend")
            val app = launchWithProject(project, teamPreferences(globalTeams(team, other)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamDelete(team.id)).performClick()
            // The first click only asks: the team is still there, and shows that it waits for the answer.
            assertEquals("Sure?", textOf(AgentTags.agentTeamDelete(team.id)))
            assertEquals(2, teamsOf(app).size)
            awaitTag(AgentTags.agentTeamDelete(team.id)).performClick()
            awaitCondition { teamsOf(app).map { it.team.id } == listOf(other.id) }
            awaitNoTag(AgentTags.agentTeam(team.id))
            awaitTag(AgentTags.agentTeam(other.id))
        }

    @Test
    fun `Save is disabled with the reason while the form is invalid`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsNotEnabled()
            assertEquals("Give the team a name", textOf(AgentTags.AGENT_TEAM_PROBLEM))
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Backend")
            // The manager and the first member have names of their own, but a second member with the same one is refused.
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performScrollTo().performClick()
            fillAgent(AgentTags.teamMember(1), "name", "Member 1")
            awaitCondition { textOf(AgentTags.AGENT_TEAM_PROBLEM) == "Two agents are named \"Member 1\"" }
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsNotEnabled()
            fillAgent(AgentTags.teamMember(1), "name", " ")
            awaitCondition { textOf(AgentTags.AGENT_TEAM_PROBLEM) == "Give every agent a name" }
            awaitField(AgentTags.teamMember(1), "remove").performClick()
            awaitField(AgentTags.teamMember(0), "remove").performClick()
            awaitCondition { textOf(AgentTags.AGENT_TEAM_PROBLEM) == "Add at least one member" }
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performScrollTo().performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_PROBLEM)
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsEnabled()
            assertEquals(0, teamsOf(app).size)
        }

    @Test
    fun `cancelling the form saves nothing and leaves the team as it was`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, teamPreferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamEdit(team.id)).performClick()
            fillAgent(AgentTags.TEAM_MANAGER, "name", "Changed")
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_FORM)
            assertEquals(listOf(team), teamsOf(app).map { it.team })
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Never saved")
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_FORM)
            assertEquals(1, teamsOf(app).size)
        }

    @Test
    fun `a team for every project and a team for this one are saved under their own keys`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            newTeam("Everywhere")
            saveTeam()
            newTeam("Here")
            awaitTag(AgentTags.AGENT_TEAM_PROJECT).performClick()
            saveTeam()
            awaitCondition { teamsOf(app).size == 2 }
            assertEquals(listOf(true, false), teamsOf(app).map { it.global })
            val saved = app.project.agent.toPreferences()
            val everywhere = saved[PreferenceKeys.AGENT_TEAMS].orEmpty()
            val here = saved[PreferenceKeys.AGENT_TEAMS_PREFIX + app.project.root.path].orEmpty()
            assertContains(everywhere, "Everywhere")
            assertFalse("Here" in everywhere)
            assertContains(here, "Here")
            assertFalse("Everywhere" in here)
        }

    @Test
    fun `the teams are back after a restart`() {
        var saved: Map<String, String> = emptyMap()
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            newTeam("Survivor")
            fillAgent(AgentTags.teamMember(0), "name", "Coder")
            saveTeam()
            awaitCondition { teamsOf(app).size == 1 }
            saved = app.project.agent.toPreferences()
        }
        runComposeUiTest {
            val store = InMemoryPreferenceStore(saved + (PreferenceKeys.AGENT_MODE to "true"))
            val app = launchWithProject(project, store, agents = servers.settings())
            startConversation(app)
            openTeams()
            val team = onlyTeam(app).team
            assertEquals("Survivor", team.name)
            assertEquals(listOf("Coder"), team.members.map { it.name })
            awaitTag(AgentTags.agentTeam(team.id))
        }
    }

    @Test
    fun `a damaged saved team is ignored and the others show`() =
        runComposeUiTest {
            val good = sampleTeam("Good")
            val damaged = PreferenceKeys.AGENT_TEAMS to "[" + globalTeams(good).second.removePrefix("[").removeSuffix("]") + ", {\"id\": \"x\"}, 3]"
            val app = launchWithProject(project, teamPreferences(damaged), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeam(good.id))
            assertEquals(listOf(good.id), teamsOf(app).map { it.team.id })
        }

    @Test
    fun `the model and the effort dropdowns follow the model chosen`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            newTeam("Models")
            val manager = AgentTags.TEAM_MANAGER
            // Fast offers no effort; the model button is enabled once the server said which models there are.
            awaitField(manager, "model", AGENT_TIMEOUT)
            assertEquals(0, onAllNodesWithTag(AgentTags.agentTeamField(manager, "effort")).fetchSemanticsNodes().size)
            chooseInAgent(manager, "model", "fake-smart")
            awaitShownChoice(manager, "model", "Smart")
            awaitField(manager, "effort", AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeamValue(manager, "effort", "high"))
            assertEquals(0, onAllNodesWithTag(AgentTags.agentTeamValue(manager, "effort", "max")).fetchSemanticsNodes().size)
            awaitTag(AgentTags.agentTeamValue(manager, "effort", "high")).performClick()
            awaitShownChoice(manager, "effort", "High")
            // Max offers more efforts than Smart, and keeps the one chosen while it is offered.
            chooseInAgent(manager, "model", "fake-max")
            awaitShownChoice(manager, "model", "Max")
            awaitShownChoice(manager, "effort", "High")
            awaitField(manager, "effort").performClick()
            awaitTag(AgentTags.agentTeamValue(manager, "effort", "max"))
            awaitTag(AgentTags.agentTeamValue(manager, "effort", "high")).performClick()
            // Back to Fast: no effort any more, and the one chosen is forgotten.
            chooseInAgent(manager, "model", "fake-fast")
            awaitNoTag(AgentTags.agentTeamField(manager, "effort"), AGENT_TIMEOUT)
            fillAgent(manager, "name", "Boss")
            saveTeam()
            val saved = onlyTeam(app).team.manager
            assertEquals(mapOf("model" to "fake-fast"), saved.settings)
        }

    @Test
    fun `the model and the effort chosen are saved and shown again when the team is edited`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            newTeam("Smart team")
            val member = AgentTags.teamMember(0)
            chooseInAgent(member, "model", "fake-smart")
            awaitShownChoice(member, "model", "Smart")
            chooseInAgent(member, "effort", "low")
            awaitShownChoice(member, "effort", "Low")
            saveTeam()
            val saved = onlyTeam(app)
            assertEquals(mapOf("model" to "fake-smart", "effort" to "low"), saved.team.members.firstOrNull()?.settings)
            awaitTag(AgentTags.agentTeamEdit(saved.team.id)).performClick()
            showAgent(member)
            awaitShownChoice(member, "model", "Smart")
            awaitShownChoice(member, "effort", "Low")
        }

    @Test
    fun `the provider menu lists the agents of the registry, without the agent of the settings`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            val manager = AgentTags.TEAM_MANAGER
            awaitShownChoice(manager, "provider", "Fake Agent")
            awaitField(manager, "provider").performClick()
            awaitTag(AgentTags.agentTeamValue(manager, "provider", "fake-agent"), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentTeamValue(manager, "provider", "unsigned-agent"))
            assertEquals(0, onAllNodesWithTag(AgentTags.agentTeamField(manager, "provider.default")).fetchSemanticsNodes().size)
        }

    @Test
    fun `skills the server found are checked and unchecked, and saved with the team`() =
        runComposeUiTest {
            project.file(".claude/skills/review/SKILL.md", "---\nname: Code review\ndescription: Reviews a diff\n---\nBody\n")
            project.file("skills/tests/SKILL.md", "No front matter\n")
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            openTeams()
            newTeam("Skilled")
            val manager = AgentTags.TEAM_MANAGER
            val review = project.root.resolve(".claude/skills/review").toString()
            val tests = project.root.resolve("skills/tests").toString()
            val reviewRow = AgentTags.agentTeamValue(manager, "skills", review)
            val testsRow = AgentTags.agentTeamValue(manager, "skills", tests)
            awaitField(manager, "skills").performClick()
            awaitTag(reviewRow, AGENT_TIMEOUT).performClick()
            awaitTag(testsRow).performClick()
            awaitTag(reviewRow).performClick()
            awaitTag(testsRow).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(testsRow)
            assertEquals("Skills (1) ▾", textOf(AgentTags.agentTeamField(manager, "skills")))
            saveTeam()
            assertEquals(listOf(tests), onlyTeam(app).team.manager.skills)
        }

    @Test
    fun `a skill that is gone is shown as not found and can be unchecked`() =
        runComposeUiTest {
            project.file("skills/tests/SKILL.md", "No front matter\n")
            val remaining = project.root.resolve("skills/tests").toString()
            val gone = project.root.resolve("skills/gone").toString()
            val base = sampleTeam("Backend")
            val team = base.copy(manager = base.manager.copy(skills = listOf(gone)))
            val app = launchWithProject(project, teamPreferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeamEdit(team.id)).performClick()
            val manager = AgentTags.TEAM_MANAGER
            awaitField(manager, "skills").performClick()
            val row = AgentTags.agentTeamValue(manager, "skills", gone)
            awaitTag(row, AGENT_TIMEOUT)
            assertContains(textOf(row), "Not found")
            awaitTag(row).performClick()
            // Unchecked, it is no longer listed: the skills that exist are.
            awaitNoTag(row)
            val listed = AgentTags.agentTeamValue(manager, "skills", remaining)
            awaitTag(listed)
            // A click outside the menu closes it.
            awaitTag(AgentTags.AGENT_TEAMS_DIALOG).performMouseInput { click(Offset(CORNER, CORNER)) }
            awaitNoTag(listed)
            saveTeam()
            assertEquals(emptyList(), onlyTeam(app).team.manager.skills)
        }
}
