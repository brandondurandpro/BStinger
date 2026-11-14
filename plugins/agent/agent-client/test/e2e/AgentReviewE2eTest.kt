package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.DEFAULT_REVIEW_ASPECTS
import org.example.kotlineditor.state.ReviewAspect
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.state.reviewPrompt
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.EditorTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Review ▾" in the card of the changes: by hand in a review tab, or by an agent that looks at the aspects ticked. */
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
    fun `the prompt lists only the aspects asked for`() {
        val conversation = Conversation(1, "t", "main")
        val prompt = reviewPrompt(conversation, setOf(ReviewAspect.Tests, ReviewAspect.Security))
        assertTrue(ReviewAspect.Tests.label in prompt && ReviewAspect.Security.label in prompt, prompt)
        assertFalse(ReviewAspect.Performance.label in prompt, prompt)
    }

    @Test
    fun `Manually opens a review tab of the changed files`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = conversationWithChange(app)
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_MANUAL).performClick()
            awaitTag(EditorTags.REVIEW)
            val tab = conversation.workspace?.editor?.activeTab
            val review = tab?.status as? TabStatus.Review
            val state = assertNotNull(review).state
            assertEquals(listOf("agent-change.txt"), state.files.map { it.relativePath })
        }

    @Test
    fun `By agent asks the aspects, and the review conversation is sent only those`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            conversationWithChange(app)
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_BY_AGENT).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_DIALOG)
            // Tests is ticked by default: untick it, and tick Security.
            awaitTag(AgentTags.agentReviewAspect(ReviewAspect.Tests.name)).performClick()
            awaitTag(AgentTags.agentReviewAspect(ReviewAspect.Security.name)).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_START).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_DEFAULT).performClick()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.conversations.any { it.title.startsWith("Review:") } }
            val reviewer = app.project.agent.conversations.first { it.title.startsWith("Review:") }
            awaitCondition(AGENT_TIMEOUT) { reviewer.itemsOf<AgentItem.UserText>().isNotEmpty() }
            val sent = reviewer.itemsOf<AgentItem.UserText>().first().text
            assertTrue(ReviewAspect.Security.label in sent, sent)
            assertFalse(ReviewAspect.Tests.label in sent, sent)
        }

    @Test
    fun `Cancel closes the dialog and starts nothing`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            conversationWithChange(app)
            val known = app.project.agent.conversations.size
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_BY_AGENT).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_CANCEL).performClick()
            awaitNoTag(AgentTags.AGENT_REVIEW_DIALOG)
            assertEquals(known, app.project.agent.conversations.size)
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
    fun `without changes, the Review button stays under the prompt and Manually is disabled`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo started")
            awaitAnswer(conversation, "started")
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_MANUAL).assertIsNotEnabled()
            awaitTag(AgentTags.AGENT_REVIEW_BY_AGENT).assertIsEnabled()
        }

    @Test
    fun `Start is disabled while no aspect is ticked`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            conversationWithChange(app)
            awaitTag(AgentTags.AGENT_REVIEW).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_BY_AGENT).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_START).assertIsEnabled()
            for (aspect in DEFAULT_REVIEW_ASPECTS) awaitTag(AgentTags.agentReviewAspect(aspect.name)).performClick()
            awaitTag(AgentTags.AGENT_REVIEW_START).assertIsNotEnabled()
        }
}

// A line of the labels at 12 sp is about 16 px: a button cut to nothing is far under.
private const val MIN_BUTTON_HEIGHT = 12
