package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** What the agent changed shows in a card at the right of a wide conversation, in a bar above the prompt otherwise. */
@OptIn(ExperimentalTestApi::class)
class AgentChangesCardE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the changes are in a card at the right of a wide conversation`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            app.project.agent.conversationsVisible = false
            changeAFile(conversation.workspace?.root?.path)
            app.project.agent.refreshChanges(conversation)
            val card = awaitTag(AgentTags.agentChanges(conversation.id)).fetchSemanticsNode().boundsInRoot
            val view = awaitTag(AgentTags.agentView(conversation.id)).fetchSemanticsNode().boundsInRoot
            assertTrue(card.left > (view.left + view.right) / 2, "the card is in the right half: $card in $view")
            assertTrue(card.top < view.bottom - 100, "the card is not under the prompt: $card")
        }

    private fun changeAFile(root: String?) {
        val directory = checkNotNull(root) { "The conversation has no workspace" }
        File(directory, "agent-change.txt").writeText("changed\n")
    }

    private fun ComposeUiTest.shows(text: String): Boolean {
        val matcher = hasText(text, substring = true)
        return onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the Informations card gives the branch or the worktree and the path, and the place is fixed once started`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            app.project.agent.conversationsVisible = false
            awaitTag(AgentTags.AGENT_MODE_WORKTREE)
            val card = awaitTag(AgentTags.AGENT_LOCATION_CARD).fetchSemanticsNode().boundsInRoot
            val view = awaitTag(AgentTags.agentView(conversation.id)).fetchSemanticsNode().boundsInRoot
            assertTrue(card.left > (view.left + view.right) / 2, "the card is in the right half: $card in $view")
            assertTrue(card.top < view.top + 100, "the card is at the top: $card in $view")
            awaitCondition { shows("Informations") && shows(project.root.toString()) && shows("Branch") }
            // The path is in full, on several lines if need be: its box is taller than one line of text.
            val path = awaitTag(AgentTags.AGENT_INFO_PATH, unmerged = true).fetchSemanticsNode().boundsInRoot
            assertTrue(path.height > LINE_HEIGHT * 2, "the path wraps instead of being cut: $path")
            // In a worktree the card names the worktree and its own folder.
            awaitTag(AgentTags.AGENT_MODE_WORKTREE).performClick()
            awaitCondition { conversation.mode == WorkMode.Worktree && shows("Worktree") && shows(conversation.branch) }
            sendPrompt("echo started")
            awaitAnswer(conversation, "started")
            awaitNoTag(AgentTags.AGENT_MODE_WORKTREE)
            awaitNoTag(AgentTags.AGENT_MODE_DIRECT)
        }
}

// Two lines of the labels at 12 sp, in pixels at the density of the tests, are about 40; a title and one line, less.
private const val LINE_HEIGHT = 16
