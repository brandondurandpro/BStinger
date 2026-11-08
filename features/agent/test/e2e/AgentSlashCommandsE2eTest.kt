package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The slash commands of the agent in the prompt: a `/` at its start lists those the agent offers (before the first
 * prompt too), typing filters them, the arrows, Enter, Tab and a click choose one, Escape closes the list.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSlashCommandsE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a slash lists the commands of the agent before the first prompt, and Enter completes then sends`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            awaitModel("Fast")
            typePrompt("/")
            awaitTag(AgentTags.agentCompletion("/count"), timeout = AGENT_TIMEOUT)
            assertEquals(listOf("/echo", "/count", "/model", "/plan", "/usage"), suggestions())
            assertTrue("Counts the prompts of the session" in textOf(AgentTags.AGENT_COMPLETION))
            typePrompt("co")
            awaitNoTag(AgentTags.agentCompletion("/echo"))
            assertEquals(listOf("/count"), suggestions())
            pressInPrompt(Key.Enter)
            awaitNoTag(AgentTags.AGENT_COMPLETION)
            assertEquals("/count ", conversation.draft)
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Prompt 1 of")
            awaitIdle(conversation)
            assertEquals("/count", conversation.itemsOf<AgentItem.UserText>().single().text)
        }

    @Test
    fun `a command chosen with a click shows what to type after it`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            typePrompt("/")
            awaitTag(AgentTags.agentCompletion("/echo"), timeout = AGENT_TIMEOUT)
            assertTrue("text to echo" in textOf(AgentTags.agentCompletion("/echo")))
            awaitTag(AgentTags.agentCompletion("/echo"), timeout = AGENT_TIMEOUT).performClick()
            typePrompt("from a click")
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "from a click")
            awaitIdle(conversation)
        }

    @Test
    fun `the arrows and Tab choose, Escape closes the list and Enter sends as typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            typePrompt("/")
            awaitTag(AgentTags.agentCompletion("/plan"), timeout = AGENT_TIMEOUT)
            pressInPrompt(Key.DirectionDown)
            pressInPrompt(Key.DirectionDown)
            pressInPrompt(Key.DirectionUp)
            pressInPrompt(Key.Tab)
            assertEquals("/count ", conversation.draft)
            conversation.draft = ""
            typePrompt("/mo")
            awaitTag(AgentTags.agentCompletion("/model"))
            pressInPrompt(Key.Escape)
            awaitNoTag(AgentTags.AGENT_COMPLETION)
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "You said: /mo")
            awaitIdle(conversation)
        }

    @Test
    fun `a slash inside the prompt completes nothing`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            awaitModel("Fast")
            typePrompt("echo a /co")
            assertFalse(isShown(AgentTags.AGENT_COMPLETION))
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "a /co")
            awaitIdle(conversation)
        }

    @Test
    fun `an agent without commands lists none, and the prompt is sent as typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--no-commands"))
            val conversation = startConversation(app)
            awaitModel("Fast")
            typePrompt("/echo plain")
            assertFalse(isShown(AgentTags.AGENT_COMPLETION))
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "plain")
            awaitIdle(conversation)
        }
}
