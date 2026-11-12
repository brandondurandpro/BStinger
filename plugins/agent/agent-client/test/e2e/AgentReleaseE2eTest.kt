package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A conversation whose tab is closed holds nothing in memory when its agent can replay it: it stays in the list, and
 * opens again with its messages, in the same session. One that would lose something keeps all it shows.
 */
@OptIn(ExperimentalTestApi::class)
class AgentReleaseE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences(vararg options: String) = agentPreferences("--store", "$store", *options)

    private fun messagesOf(conversation: Conversation): List<String> =
        conversation.items.mapNotNull { item ->
            when (item) {
                is AgentItem.UserText -> item.text
                is AgentItem.AgentText -> item.text
                else -> null
            }
        }

    @Test
    fun `a conversation is released when its tab closes, and opens again with its messages and its draft`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("echo hello release")
            awaitAnswer(conversation, "hello release")
            awaitIdle(conversation)
            typePrompt("a draft")
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            assertTrue(conversation.items.isEmpty(), "${conversation.items.size} items held")
            // Still listed, under its title and its last line.
            assertEquals("echo hello release", conversation.title)
            assertEquals("hello release ", conversation.preview)
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "hello release")
            awaitIdle(conversation)
            assertEquals(listOf("echo hello release", "hello release "), messagesOf(conversation))
            assertEquals("a draft", conversation.draft)
        }

    @Test
    fun `a conversation opened again goes on in its session`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("echo first")
            awaitAnswer(conversation, "first")
            awaitIdle(conversation)
            val session = conversation.session
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "first")
            awaitIdle(conversation)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 2 of $session.")
            awaitIdle(conversation)
            assertEquals(listOf("echo first", "first ", "count", "Prompt 2 of $session."), messagesOf(conversation))
        }

    @Test
    fun `closed and opened several times, a conversation shows its messages once`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("echo again")
            awaitAnswer(conversation, "again")
            awaitIdle(conversation)
            repeat(3) {
                awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
                awaitNoTag(AgentTags.agentTab(conversation.id))
                awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
                awaitAnswer(conversation, "again")
                awaitIdle(conversation)
            }
            assertEquals(listOf("echo again", "again "), messagesOf(conversation))
        }

    @Test
    fun `a conversation whose agent cannot replay keeps its messages when its tab closes`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences("--no-load"))
            val conversation = startConversation(app)
            sendPrompt("echo kept")
            awaitAnswer(conversation, "kept")
            awaitIdle(conversation)
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            assertEquals(listOf("echo kept", "kept "), messagesOf(conversation))
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "kept")
            assertEquals(listOf("echo kept", "kept "), messagesOf(conversation))
        }

    @Test
    fun `a conversation whose agent answers goes on when its tab closes`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("slow")
            awaitShown(conversation, "tick 1 ")
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            assertTrue(conversation.busy)
            assertFalse(conversation.items.isEmpty())
            // The answer goes on streaming behind the closed tab.
            awaitCondition(AGENT_TIMEOUT) { messagesOf(conversation).any { "tick 10 " in it } }
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            onNodeWithTag(AgentTags.AGENT_STOP).performClick()
            awaitIdle(conversation)
            assertEquals("slow", messagesOf(conversation).firstOrNull())
        }

    @Test
    fun `a new conversation that said nothing closes and opens empty`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            sendPrompt("echo late start")
            awaitAnswer(conversation, "late start")
            awaitIdle(conversation)
            assertEquals(listOf("echo late start", "late start "), messagesOf(conversation))
        }
}
