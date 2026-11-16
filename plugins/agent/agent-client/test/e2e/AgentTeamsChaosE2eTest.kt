package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the Teams window asks the server (the skills, and the models and efforts of a provider) when the network or the
 * server fails: the form stays usable, shows "Default" and a wait instead of an answer, nothing stays loading once the
 * link is back, and on a slow link the last choice wins.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsChaosE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()
    private val manager = AgentTags.TEAM_MANAGER

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
    }

    private fun ComposeUiTest.noNodes(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    @Test
    fun `with the network down the form stays usable and the models come once it is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings())
            startConversation(app)
            // The agent of the conversation downloaded and started: the models are known before the cut.
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT)
            app.network.goDown()
            awaitReconnecting()
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            // The models cannot be read: "Default", disabled, and the rest of the form works.
            awaitTag(AgentTags.agentTeamField(manager, "model.unavailable"))
            fillAgent(manager, "name", "Boss")
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Offline team")
            saveTeam()
            assertEquals("Offline team", app.project.agent.teams.teams.lastOrNull()?.team?.name)
            val saved = checkNotNull(app.project.agent.teams.teams.lastOrNull()).team
            app.network.comeBack()
            awaitReconnected()
            awaitTag(AgentTags.agentTeamEdit(saved.id)).performClick()
            // Asked again when the form opens: the models are offered, nothing stays loading.
            awaitField(manager, "model", AGENT_TIMEOUT)
            assertTrue(noNodes(AgentTags.agentTeamField(manager, "model.unavailable")))
        }

    @Test
    fun `the skills asked while the network is down are listed once it is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            project.file(".claude/skills/review/SKILL.md", "---\nname: Code review\n---\n")
            val app = launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings())
            startConversation(app)
            app.network.goDown()
            awaitReconnecting()
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitField(manager, "skills").performClick()
            val review = project.root.resolve(".claude/skills/review").toString()
            val row = AgentTags.agentTeamValue(manager, "skills", review)
            // Not listed yet: the menu waits, and the form stays as it is.
            assertTrue(noNodes(row))
            app.network.comeBack()
            awaitReconnected()
            awaitTag(row, AGENT_TIMEOUT)
        }

    @Test
    fun `a link that goes silent is waited for, then the models and the skills come`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings())
            startConversation(app)
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT)
            app.network.blackHole = true
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.agentTeamField(manager, "model.unavailable"))
            awaitTag(AgentTags.AGENT_TEAM_SAVE)
            app.network.blackHole = false
            app.network.cutAll()
            awaitReconnected(AGENT_TIMEOUT)
            awaitField(manager, "model", AGENT_TIMEOUT)
        }

    @Test
    fun `on a slow link the last model chosen wins and the efforts are its own`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitField(manager, "model", AGENT_TIMEOUT)
            app.network.latencyMs = 400
            chooseInAgent(manager, "model", "fake-max")
            chooseInAgent(manager, "model", "fake-smart")
            awaitShownChoice(manager, "model", "Smart")
            // The answer for Max, if it comes late, must not bring its Max effort back.
            awaitField(manager, "effort", AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeamValue(manager, "effort", "high"), AGENT_TIMEOUT)
            assertTrue(noNodes(AgentTags.agentTeamValue(manager, "effort", "max")))
            app.network.latencyMs = 0
            assertEquals(0, onAllNodesWithTag(AgentTags.agentTeamValue(manager, "effort", "max")).fetchSemanticsNodes().size)
        }

    @Test
    fun `after the server restarts the window asks for the skills and the models again`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            project.file("skills/tests/SKILL.md", "No front matter\n")
            val app = launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitField(manager, "model", AGENT_TIMEOUT)
            app.process.restart()
            awaitReconnected(AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_TEAMS_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAMS_DIALOG)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitField(manager, "model", AGENT_TIMEOUT)
            awaitField(manager, "skills").performClick()
            val tests = project.root.resolve("skills/tests").toString()
            awaitTag(AgentTags.agentTeamValue(manager, "skills", tests), AGENT_TIMEOUT)
        }
}
