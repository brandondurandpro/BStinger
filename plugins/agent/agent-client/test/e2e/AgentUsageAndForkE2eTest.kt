package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** What the agent says of its context and cost, and a conversation duplicated with `session/fork`. */
@OptIn(ExperimentalTestApi::class)
class AgentUsageAndForkE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the context used and the cost show next to the prompt`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo hello")
            awaitAnswer(conversation, "hello")
            awaitIdle(conversation)
            awaitCondition { conversation.usage != null }
            assertContains(textOf(AgentTags.AGENT_USAGE), "0% · 1k/200k · \$0.01")
            // The list adds up what the conversations cost.
            assertContains(textOf(AgentTags.AGENT_COST), "\$0.01")
        }

    @Test
    fun `the context and the cost are in the card beside a wide conversation`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            app.project.agent.conversationsVisible = false
            sendPrompt("echo hello")
            awaitShown(conversation, "hello")
            awaitCondition { conversation.usage != null }
            assertContains(textOf(AgentTags.AGENT_CARD_CONTEXT), "1k/200k")
            assertContains(textOf(AgentTags.AGENT_CARD_COST), "\$0.01")
        }

    @Test
    fun `a duplicated conversation opens with the history of the first`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val original = startConversation(app)
            sendPrompt("echo copied-words")
            awaitAnswer(original, "copied-words")
            awaitIdle(original)
            rightClickOn(AgentTags.agentConversation(original.id))
            awaitTag(AgentTags.AGENT_DUPLICATE).performClick()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.current?.id != original.id }
            val copy = checkNotNull(app.project.agent.current)
            assertContains(copy.title, "(copy)")
            awaitShown(copy, "copied-words")
            assertEquals(2, app.project.agent.conversations.size)
        }
}
