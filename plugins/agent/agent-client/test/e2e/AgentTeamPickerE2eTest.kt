package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.rpc.teamsToText
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The team picker of the prompt bar: a conversation chooses a team or none before its first prompt, the team hides the
 * agent, the model and the effort pickers (the permissions stay), and is locked at the first prompt, saved with the
 * conversation, and followed when edited or deleted in the Teams window.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamPickerE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    // The fake agent, which offers models, efforts and permissions, with [more] preferences (the teams).
    private fun preferences(vararg more: Pair<String, String>) =
        InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + more)

    // The pickers the agent offers appear one after the other and move the team picker: wait for the last of them.
    private fun ComposeUiTest.openPickerMenu() {
        awaitTag(AgentTags.AGENT_PERMISSIONS, timeout = AGENT_TIMEOUT)
        awaitTag(AgentTags.AGENT_TEAM_PICKER).performClick()
        awaitTag(AgentTags.AGENT_TEAM_PICKER_NONE)
    }

    private fun ComposeUiTest.chooseTeam(id: String) {
        openPickerMenu()
        awaitTag(AgentTags.agentTeamChoice(id)).performClick()
        awaitNoTag(AgentTags.AGENT_TEAM_PICKER_NONE)
    }

    private fun ComposeUiTest.chooseNoTeam() {
        openPickerMenu()
        awaitTag(AgentTags.AGENT_TEAM_PICKER_NONE).performClick()
        awaitNoTag(AgentTags.AGENT_TEAM_PICKER_NONE)
    }

    private fun ComposeUiTest.awaitPicker(text: String) {
        awaitTag(AgentTags.AGENT_TEAM_PICKER)
        awaitCondition { textOf(AgentTags.AGENT_TEAM_PICKER).startsWith(text) }
    }

    private fun ComposeUiTest.isGone(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    private fun ComposeUiTest.startedWith(app: E2eApp, prompt: String): Conversation {
        val conversation = startConversation(app)
        return conversation.also {
            sendPrompt(prompt)
            awaitAnswer(it, "hi")
            awaitIdle(it)
        }
    }

    private fun ComposeUiTest.closeTeams() {
        awaitTag(AgentTags.AGENT_TEAMS_CLOSE).performClick()
        awaitNoTag(AgentTags.AGENT_TEAMS_DIALOG)
    }

    @Test
    fun `a new conversation has no team, and the picker says so`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitPicker("No team")
            assertNull(conversation.teamId)
            assertNull(app.project.agent.sendingTeam(conversation))
        }

    @Test
    fun `choosing a team hides the agent, model and effort pickers and keeps the permissions, no team brings them back`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            awaitModel("Fast")
            chooseModel("fake-smart")
            awaitEffort("Default")
            chooseEffort("high")
            awaitEffort("High")
            awaitPermissions("Manual")
            awaitTag(AgentTags.AGENT_AGENT)
            chooseTeam(team.id)
            awaitPicker("Backend")
            awaitNoTag(AgentTags.AGENT_AGENT)
            awaitNoTag(AgentTags.AGENT_MODEL)
            awaitNoTag(AgentTags.AGENT_EFFORT)
            awaitTag(AgentTags.AGENT_PERMISSIONS)
            assertEquals(team.id, conversation.teamId)
            assertEquals(team.id, app.project.agent.sendingTeam(conversation)?.team?.id)
            chooseNoTeam()
            awaitPicker("No team")
            awaitTag(AgentTags.AGENT_AGENT)
            awaitModel("Smart")
            awaitEffort("High")
            assertNull(conversation.teamId)
        }

    @Test
    fun `the menu lists the global teams and this project's, not another project's`() =
        runComposeUiTest {
            val everywhere = sampleTeam("Everywhere")
            val elsewhere = sampleTeam("Elsewhere")
            val other = PreferenceKeys.AGENT_TEAMS_PREFIX + "/some/other/project" to listOf(elsewhere).teamsToText()
            val app = launchWithProject(project, preferences(globalTeams(everywhere), other))
            val here = sampleTeam("Here")
            runOnIdle { app.project.agent.teams.save(here, global = false) }
            startConversation(app)
            openPickerMenu()
            awaitTag(AgentTags.agentTeamChoice(everywhere.id))
            awaitTag(AgentTags.agentTeamChoice(here.id))
            awaitTag(AgentTags.AGENT_TEAM_PICKER_MANAGE)
            assertTrue(isGone(AgentTags.agentTeamChoice(elsewhere.id)))
        }

    @Test
    fun `Manage teams opens the Teams window`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            startConversation(app)
            openPickerMenu()
            awaitTag(AgentTags.AGENT_TEAM_PICKER_MANAGE).performClick()
            awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
            awaitTag(AgentTags.agentTeam(team.id))
            assertTrue(isGone(AgentTags.AGENT_TEAM_PICKER_NONE))
        }

    @Test
    fun `with no team defined the menu still offers Manage teams`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            startConversation(app)
            openPickerMenu()
            assertTrue(isGone(AgentTags.AGENT_TEAM_PICKER_LIST))
            awaitTag(AgentTags.AGENT_TEAM_PICKER_MANAGE).performClick()
            awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
            awaitTag(AgentTags.AGENT_TEAMS_EMPTY)
        }

    @Test
    fun `a team created in the window is in the menu without reopening the conversation`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            openPickerMenu()
            awaitTag(AgentTags.AGENT_TEAM_PICKER_MANAGE).performClick()
            awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
            newTeam("Fresh")
            fillAgent(AgentTags.teamMember(0), "name", "Coder")
            saveTeam()
            awaitCondition { app.project.agent.teams.teams.size == 1 }
            val created = app.project.agent.teams.teams.map { it.team }.single()
            closeTeams()
            chooseTeam(created.id)
            awaitPicker("Fresh")
            assertEquals(created.id, conversation.teamId)
        }

    @Test
    fun `the picker is locked at the first prompt, without a team`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startedWith(app, "echo hi")
            awaitPicker("No team")
            awaitTag(AgentTags.AGENT_TEAM_PICKER).assertIsNotEnabled()
            awaitTag(AgentTags.AGENT_TEAM_PICKER).performClick()
            waitForIdle()
            assertTrue(isGone(AgentTags.AGENT_TEAM_PICKER_NONE))
            app.project.agent.chooseTeam(conversation, team.id)
            assertNull(conversation.teamId)
        }

    @Test
    fun `the picker is locked at the first prompt, with a team, and shows its name`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_TEAM_PICKER).assertIsEnabled()
            chooseTeam(team.id)
            sendPrompt("echo hi")
            awaitAnswer(conversation, "hi")
            awaitIdle(conversation)
            awaitPicker("Backend")
            awaitTag(AgentTags.AGENT_TEAM_PICKER).assertIsNotEnabled()
            awaitTag(AgentTags.AGENT_TEAM_PICKER).performClick()
            waitForIdle()
            assertTrue(isGone(AgentTags.AGENT_TEAM_PICKER_NONE))
            app.project.agent.chooseTeam(conversation, null)
            assertEquals(team.id, conversation.teamId)
            // Today a prompt still goes to the agent of the conversation: the answer came.
            assertFalse(conversation.teamMissing)
        }

    @Test
    fun `the team of a started conversation is back after a restart`() {
        val team = sampleTeam("Backend")
        var saved: Map<String, String> = emptyMap()
        var session = ""
        runComposeUiTest {
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            chooseTeam(team.id)
            sendPrompt("echo hi")
            awaitAnswer(conversation, "hi")
            awaitIdle(conversation)
            val key = PreferenceKeys.AGENT_CONVERSATION_TEAMS_PREFIX + app.project.root.path
            // Saved once the history was listed and the session of the conversation is known.
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.toPreferences()[key].orEmpty().isNotBlank() }
            saved = app.project.agent.toPreferences()
            session = checkNotNull(conversation.session) { "No session" }
            assertEquals("$session\t${team.id}", saved[key])
        }
        runComposeUiTest {
            val app = launchWithProject(project, InMemoryPreferenceStore(preferences().load() + saved))
            val conversation = awaitHistory(app, session)
            awaitCondition(AGENT_TIMEOUT) { conversation.teamId == team.id }
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitPicker("Backend")
            awaitTag(AgentTags.AGENT_TEAM_PICKER).assertIsNotEnabled()
            awaitNoTag(AgentTags.AGENT_MODEL)
        }
    }

    @Test
    fun `an edited team shows its new name`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            chooseTeam(team.id)
            awaitPicker("Backend")
            openTeams()
            awaitTag(AgentTags.agentTeamEdit(team.id)).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextReplacement("Platform")
            saveTeam()
            closeTeams()
            awaitPicker("Platform")
            assertEquals(team.id, conversation.teamId)
            assertEquals("Platform", conversation.team?.team?.name)
        }

    private fun ComposeUiTest.deleteTeam(id: String) {
        openTeams()
        awaitTag(AgentTags.agentTeamDelete(id)).performClick()
        awaitTag(AgentTags.agentTeamDelete(id)).performClick()
        awaitNoTag(AgentTags.agentTeam(id))
        closeTeams()
    }

    @Test
    fun `a team deleted before the conversation started falls back to no team`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            awaitModel("Fast")
            chooseTeam(team.id)
            awaitNoTag(AgentTags.AGENT_MODEL)
            deleteTeam(team.id)
            awaitPicker("No team")
            awaitTag(AgentTags.AGENT_MODEL)
            assertNull(conversation.teamId)
            assertFalse(conversation.teamMissing)
            assertTrue(isGone(AgentTags.AGENT_TEAM_MISSING))
        }

    @Test
    fun `a team deleted after the conversation started shows a notice and the missing state`() =
        runComposeUiTest {
            val team = sampleTeam("Backend")
            val app = launchWithProject(project, preferences(globalTeams(team)))
            val conversation = startConversation(app)
            chooseTeam(team.id)
            sendPrompt("echo hi")
            awaitAnswer(conversation, "hi")
            awaitIdle(conversation)
            assertTrue(isGone(AgentTags.AGENT_TEAM_MISSING))
            deleteTeam(team.id)
            awaitTag(AgentTags.AGENT_TEAM_MISSING)
            awaitPicker("Deleted team")
            awaitTag(AgentTags.AGENT_TEAM_PICKER).assertIsNotEnabled()
            assertTrue(conversation.teamMissing)
            assertEquals(team.id, conversation.teamId)
            assertNull(app.project.agent.sendingTeam(conversation))
            // The agent is not changed silently: its pickers stay hidden.
            assertTrue(isGone(AgentTags.AGENT_AGENT))
        }
}
