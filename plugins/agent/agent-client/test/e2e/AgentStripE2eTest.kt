package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.IdeColors
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The team picker of a conversation, and the strip above it: the manager (♛) and the sub-agents it launched, each a
 * chip that shows what it did. The strip reads the transcript only: it asks the server nothing.
 */
@OptIn(ExperimentalTestApi::class)
class AgentStripE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    // A build that saved before the default team existed left an empty value.
    private fun preferencesWithEmptyTeams() =
        preferences().also { it.save(it.load() + (PreferenceKeys.AGENT_TEAMS to "")) }

    private fun androidx.compose.ui.test.ComposeUiTest.chooseDefaultTeam() {
        awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
        awaitTag(AgentTags.agentTeam("Default team")).performClick()
    }

    // The first prompt of a team carries its briefing, which hides a command of the fake agent: it is spent on a greeting.
    private fun androidx.compose.ui.test.ComposeUiTest.chooseDefaultTeamAndGreet(conversation: org.example.kotlineditor.state.Conversation) {
        chooseDefaultTeam()
        awaitTag(AgentTags.agentStripEntry("Manager"))
        sendPrompt("hello")
        awaitShown(conversation, "You said")
        awaitIdle(conversation)
    }

    @Test
    fun `preferences saved with no team still offer the default team to a conversation`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferencesWithEmptyTeams())
            val conversation = startConversation(app)
            assertEquals(listOf("Default team"), app.project.agent.teams.map { it.name })
            awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NONE)
            awaitTag(AgentTags.agentTeam("Default team")).performClick()
            awaitCondition { conversation.team == "Default team" }
        }

    @Test
    fun `the team is chosen after a first turn, and the next prompt carries its briefing`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("hello")
            awaitIdle(conversation)
            chooseDefaultTeam()
            awaitCondition { conversation.team == "Default team" }
            sendPrompt("echo again")
            awaitShown(conversation, "manager of the team")
            awaitIdle(conversation)
        }

    @Test
    fun `Make a team opens the window of the teams`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            startConversation(app)
            awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEAM_MAKE).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW)
            awaitTag(AgentTags.AGENT_TEAM_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_CLOSE)
        }

    @Test
    fun `no strip without a team or a sub-agent`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("hello")
            awaitIdle(conversation)
            assertFalse(isShown(AgentTags.AGENT_STRIP))
        }

    @Test
    fun `a team shows its manager in the strip, and a sub-agent launched shows with its task and report`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseDefaultTeamAndGreet(conversation)
            assertFalse(isShown(AgentTags.agentStripEntry("Dev")))
            sendPrompt("delegate Dev: add the button")
            awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
            awaitTag(AgentTags.agentStripEntry("Dev")).performClick()
            awaitTag(AgentTags.AGENT_STRIP_DETAIL)
            val shown = textOf(AgentTags.AGENT_STRIP_DETAIL)
            assertTrue("Task" in shown && "Dev: add the button" in shown, shown)
            assertTrue("Report" in shown && "Report of Dev: add the button: done." in shown, shown)
            assertTrue("done" in shown, shown)
            awaitTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_STRIP_DETAIL)
        }

    // The layout of the text that contains [part], inside the open detail window: its lines and its color.
    private fun androidx.compose.ui.test.ComposeUiTest.layoutOf(part: String): TextLayoutResult {
        val nodes = onAllNodes(hasText(part, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        val node = nodes.firstOrNull { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val action = checkNotNull(node?.config?.getOrNull(SemanticsActions.GetTextLayoutResult)?.action) { "No text with '$part'" }
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return checkNotNull(results.firstOrNull()) { "No layout for '$part'" }
    }

    @Test
    fun `the task and the report of a sub-agent are read in the theme color, the whole report shown`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseDefaultTeamAndGreet(conversation)
            sendPrompt("delegate Dev: add the button")
            awaitIdle(conversation)
            awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_STRIP_DETAIL)
            val shown = textOf(AgentTags.AGENT_STRIP_DETAIL)
            assertTrue("Report of Dev: add the button: done." in shown && "Second line of the report." in shown, shown)
            val report = layoutOf("Second line of the report.")
            assertEquals(IdeColors.Text, report.layoutInput.style.color)
            assertTrue(report.lineCount >= 2, "the report is cut to ${report.lineCount} line(s)")
            val task = layoutOf("Dev: add the button")
            assertEquals(IdeColors.Text, task.layoutInput.style.color)
            awaitTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE).performClick()
        }

    @Test
    fun `a sub-agent still working has no report yet`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseDefaultTeamAndGreet(conversation)
            sendPrompt("delegating Dev: add the button")
            awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_STRIP_DETAIL)
            val shown = textOf(AgentTags.AGENT_STRIP_DETAIL)
            assertTrue("No report yet." in shown, shown)
            // The turn ended without the call: the client settles it as done, with no report.
            assertFalse("Report of" in shown, shown)
            awaitTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE).performClick()
            awaitIdle(conversation)
        }

    @Test
    fun `a sub-agent that no member matches is named by its title`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseDefaultTeamAndGreet(conversation)
            sendPrompt("delegate Review the docs")
            awaitTag(AgentTags.agentStripEntry("Review the docs"), timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
        }

    @Test
    fun `the manager chip lists its own calls and not the launches`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseDefaultTeamAndGreet(conversation)
            sendPrompt("tools 2")
            awaitIdle(conversation)
            sendPrompt("delegate Tester: run it")
            awaitTag(AgentTags.agentStripEntry("Tester"), timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
            awaitTag(AgentTags.agentStripEntry("Manager")).performClick()
            awaitTag(AgentTags.AGENT_STRIP_DETAIL)
            val shown = textOf(AgentTags.AGENT_STRIP_DETAIL)
            assertTrue("Search step 0" in shown && "Search step 1" in shown, shown)
            assertFalse("Tester: run it" in shown, shown)
            awaitTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_STRIP_DETAIL)
        }

    @Test
    fun `a sub-agent without a team shows alone in the strip`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("delegate Explore the repository")
            awaitTag(AgentTags.agentStripEntry("Explore the repository"), timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
        }
}
