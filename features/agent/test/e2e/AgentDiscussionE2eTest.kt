package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.parseMove
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two agents discuss a request: the first asks, the other answers, then the first gives the plan and carries it out. */
@OptIn(ExperimentalTestApi::class)
class AgentDiscussionE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the last ASK or FINAL of an answer is the move`() {
        assertEquals("what db?", parseMove("I read it.\nASK: what db?").text)
        assertEquals("use sqlite", parseMove("ASK: x\n**FINAL:** use sqlite").text)
        assertEquals("just a question", parseMove("just a question").text)
    }

    @Test
    fun `an agent asks the other one, they agree on a plan, and the plan is carried out`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            typePrompt("discuss a cache for the tree")
            awaitTag(AgentTags.AGENT_DISCUSS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_DISCUSS_DEFAULT).performClick()
            // The leader asked, the other agent answered, the leader's plan came back.
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.AgentText>().any { "ASK: which storage" in it.text } }
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.UserText>().any { "answers:" in it.text } }
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.AgentText>().any { "FINAL: use sqlite" in it.text } }
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.Notice>().any { "the plan is carried out" in it.text } }
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.UserText>().any { "Implement the final plan" in it.text } }
            val agent = app.project.agent
            val peer = agent.conversations.firstOrNull { it.title.startsWith("Discussion:") }
            assertTrue(peer != null, "the other agent has a conversation of its own")
            val asked = peer.itemsOf<AgentItem.UserText>().firstOrNull()?.text.orEmpty()
            assertTrue("which storage do we use?" in asked, "the question reached the other agent: $asked")
        }
}
