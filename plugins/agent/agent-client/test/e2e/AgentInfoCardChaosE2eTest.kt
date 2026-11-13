package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The "Informations" card when the link to the server fails: no stale figure passes for the state of the directory. */
@OptIn(ExperimentalTestApi::class)
class AgentInfoCardChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.shows(text: String): Boolean {
        val matcher = hasText(text, substring = true)
        return onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.worktreeConversation(app: E2eApp): Conversation {
        awaitTag(AgentTags.MODE_AGENT).performClick()
        awaitTag(AgentTags.AGENT_NEW).performClick()
        awaitTag(AgentTags.agentBase("other")).performClick()
        awaitTag(AgentTags.AGENT_PROMPT)
        app.project.agent.conversationsVisible = false
        val conversation = checkNotNull(app.project.agent.current)
        awaitCondition { conversation.workspace != null && !conversation.preparingWorktree }
        awaitCondition { shows("clean") }
        return conversation
    }

    @Test
    fun `the card shows the real state of the worktree once the network is back`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "other")
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = worktreeConversation(app)
            val directory = Path.of(checkNotNull(conversation.workspace).root.path)
            File(directory.toFile(), "late.txt").writeText("x\n")
            gitIn(directory, "add", "-A")
            gitIn(directory, "commit", "-q", "-m", "committed while away")
            app.network.goDown()
            awaitReconnecting()
            app.project.agent.refreshChanges(conversation)
            app.network.comeBack()
            awaitReconnected()
            app.project.agent.refreshChanges(conversation)
            awaitCondition(AGENT_TIMEOUT) { shows("1 ahead") && shows("committed while away") }
            assertEquals(1, app.project.agent.conversations.size, "the retries started no other conversation")
        }

    @Test
    fun `a server that restarts leaves the card readable`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "other")
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = worktreeConversation(app)
            app.process.restart()
            awaitReconnected()
            app.project.agent.refreshChanges(conversation)
            awaitCondition(AGENT_TIMEOUT) { shows("up to date") && shows("clean") }
        }
}
