package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.EditorTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Review" in the card of the changes opens the manual review at once; the prompt bar has no review or discussion button. */
@OptIn(ExperimentalTestApi::class)
class AgentReviewE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.conversationWithChange(app: E2eApp): Conversation {
        val conversation = startConversation(app)
        app.project.agent.conversationsVisible = false
        sendPrompt("echo started")
        awaitAnswer(conversation, "started")
        awaitIdle(conversation)
        val directory = checkNotNull(conversation.workspace?.root?.path) { "The conversation has no workspace" }
        File(directory, "agent-change.txt").writeText("changed\n")
        app.project.agent.refreshChanges(conversation)
        awaitTag(AgentTags.agentChanges(conversation.id))
        return conversation
    }

    @Test
    fun `Review opens a review tab of the changed files, with no dialog in between`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = conversationWithChange(app)
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(EditorTags.REVIEW)
            val tab = conversation.workspace?.editor?.activeTab
            val review = tab?.status as? TabStatus.Review
            val state = assertNotNull(review).state
            assertEquals(listOf("agent-change.txt"), state.files.map { it.relativePath })
        }

    @Test
    fun `the Review button of the card is whole, even in a short window`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            conversationWithChange(app)
            val button = awaitTag(AgentTags.AGENT_REVIEW).fetchSemanticsNode().boundsInRoot
            assertTrue(button.height > MIN_BUTTON_HEIGHT, "the button is not cut: $button")
        }

    @Test
    fun `the prompt bar has no Review, Send review or Discuss button, with or without changes`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo started")
            awaitAnswer(conversation, "started")
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_AGENT)
            awaitNoTag(AgentTags.AGENT_REVIEW)
            awaitNoTag("agent.review.send")
            awaitNoTag("agent.discuss")
            val directory = checkNotNull(conversation.workspace?.root?.path) { "The conversation has no workspace" }
            File(directory, "agent-change.txt").writeText("changed\n")
            app.project.agent.refreshChanges(conversation)
            awaitTag(AgentTags.agentChanges(conversation.id))
            // The only Review button is the one of the card of the changes.
            awaitTag(AgentTags.AGENT_REVIEW)
            awaitNoTag("agent.review.send")
            awaitNoTag("agent.discuss")
        }
}

// A line of the labels at 12 sp is about 16 px: a button cut to nothing is far under.
private const val MIN_BUTTON_HEIGHT = 12
