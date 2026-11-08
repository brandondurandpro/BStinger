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
            awaitShown(conversation, "hello")
            awaitIdle(conversation)
            awaitCondition { conversation.usage != null }
            assertContains(textOf(AgentTags.AGENT_USAGE), "0% · 1k/200k · \$0.01")
        }

    @Test
    fun `a duplicated conversation opens with the history of the first`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val original = startConversation(app)
            sendPrompt("echo copied-words")
            awaitShown(original, "copied-words")
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
