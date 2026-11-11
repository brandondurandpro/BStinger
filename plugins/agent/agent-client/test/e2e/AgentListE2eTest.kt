package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The list of conversations: pinned ones first, archived ones hidden but found by the search, messages searched. */
@OptIn(ExperimentalTestApi::class)
class AgentListE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the search finds a conversation by what was said in it`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val first = startConversation(app)
            sendPrompt("echo needle-in-a-haystack")
            awaitAnswer(first, "needle-in-a-haystack")
            awaitIdle(first)
            awaitTag(AgentTags.AGENT_NEW).performClick()
            val second = checkNotNull(app.project.agent.current)
            sendPrompt("echo something else")
            awaitAnswer(second, "something else")
            awaitIdle(second)
            awaitTag(AgentTags.AGENT_FILTER).performTextInput("needle")
            awaitNoTag(AgentTags.agentConversation(second.id))
            awaitTag(AgentTags.agentConversation(first.id))
        }

    @Test
    fun `an archived conversation leaves the list and the search brings it back`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo archived-words")
            awaitAnswer(conversation, "archived-words")
            awaitIdle(conversation)
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_ARCHIVE).performClick()
            awaitCondition { conversation.archived }
            awaitNoTag(AgentTags.agentConversation(conversation.id))
            // The archived ones have a folded zone under the list.
            awaitTag(AgentTags.AGENT_ARCHIVED).performClick()
            awaitTag(AgentTags.agentConversation(conversation.id))
        }

    @Test
    fun `the search finds an archived conversation too`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo archived-words")
            awaitAnswer(conversation, "archived-words")
            awaitIdle(conversation)
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_ARCHIVE).performClick()
            awaitNoTag(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_FILTER).performTextInput("archived-words")
            awaitTag(AgentTags.agentConversation(conversation.id))
        }

    @Test
    fun `a pinned conversation goes to the top of the list`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val first = startConversation(app)
            sendPrompt("echo one")
            awaitAnswer(first, "one")
            awaitIdle(first)
            awaitTag(AgentTags.AGENT_NEW).performClick()
            val second = checkNotNull(app.project.agent.current)
            sendPrompt("echo two")
            awaitAnswer(second, "two")
            awaitIdle(second)
            rightClickOn(AgentTags.agentConversation(first.id))
            awaitTag(AgentTags.AGENT_PIN).performClick()
            awaitCondition { first.pinned }
            val order = app.project.agent.conversations.map { it.id }
            assertTrue(first.pinned)
            assertEquals(setOf(first.id, second.id), order.toSet())
        }
}
