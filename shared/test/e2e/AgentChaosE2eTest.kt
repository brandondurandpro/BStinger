package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AppTags
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent mode when the network or the server fails: the transcript loses nothing and repeats nothing, an answer to a
 * permission request is applied once, a restarted server is explained, and nothing stays stuck.
 */
@OptIn(ExperimentalTestApi::class)
class AgentChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // The numbers of the ticks of a slow answer, in the order shown.
    private fun ticks(text: String): List<Int> =
        Regex("tick (\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()

    @Test
    fun `an answer streamed while the network was down shows whole once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("slow")
            awaitShown(conversation, "tick 1 ")
            app.network.goDown()
            // Noticed within a second, while the agent goes on streaming on the server.
            awaitReconnecting()
            app.network.comeBack()
            awaitReconnected()
            awaitShown(conversation, "tick 30 ")
            onNodeWithTag(AppTags.AGENT_STOP).performClick()
            awaitIdle(conversation)
            val answer = conversation.itemsOf<AgentItem.AgentText>().single().text
            val shown = ticks(answer)
            assertEquals((0 until shown.size).toList(), shown, "every tick once, in order")
            assertContains(shownConversation(conversation), "Stopped.")
        }

    @Test
    fun `a permission answered while its answer was lost is applied once`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            val file = project.root.resolve("NOTES.md")
            sendPrompt("write $file once")
            awaitTag(AppTags.agentPermission("allow"), timeout = AGENT_TIMEOUT)
            app.network.dropFromServer = true
            onNodeWithTag(AppTags.agentPermission("allow")).performClick()
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertEquals("once", file.readText())
            assertEquals(1, conversation.itemsOf<AgentItem.Permission>().size)
            assertEquals("allow", conversation.itemsOf<AgentItem.Permission>().single().chosen)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().count { it.text.startsWith("Wrote") })
        }

    @Test
    fun `a prompt sent while the network is down is sent once when it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo warm up")
            awaitIdle(conversation)
            app.network.goDown()
            awaitReconnecting()
            sendPrompt("count")
            // Shown at once, sent when the server is back.
            awaitShown(conversation, "count")
            assertTrue(conversation.busy)
            app.network.comeBack()
            awaitReconnected()
            awaitShown(conversation, "Prompt 2 of")
            awaitIdle(conversation)
            assertEquals(2, conversation.itemsOf<AgentItem.UserText>().size)
        }

    @Test
    fun `after the server restarts the conversation says the agent forgot it, and goes on`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of")
            awaitIdle(conversation)
            app.process.restart()
            awaitReconnected()
            awaitShown(conversation, "The server restarted")
            sendPrompt("count")
            // A new agent and a new session: its first prompt. What it showed before the restart is still there.
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.AgentText>().size == 2 }
            awaitIdle(conversation)
            val answers = conversation.itemsOf<AgentItem.AgentText>().map { it.text }
            assertTrue(answers.all { it.startsWith("Prompt 1 of") }, answers.toString())
        }

    @Test
    fun `a server that restarts while the agent answers leaves nothing stuck`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("slow")
            awaitShown(conversation, "tick 1 ")
            app.process.restart()
            awaitReconnected()
            awaitIdle(conversation)
            assertContains(shownConversation(conversation), "The server restarted")
            sendPrompt("echo still here")
            awaitShown(conversation, "still here")
        }

    @Test
    fun `on a slow link the answer streams in order and the prompt stays usable`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            app.network.latencyMs = 300
            sendPrompt("stream 50")
            // Typing while the answer streams is not blocked by the link.
            sendPrompt("typed meanwhile")
            awaitShown(conversation, "chunk 49")
            awaitIdle(conversation)
            app.network.latencyMs = 0
            val answer = conversation.itemsOf<AgentItem.AgentText>().single().text
            val chunks = Regex("chunk (\\d+)").findAll(answer).map { it.groupValues[1].toInt() }.toList()
            assertEquals((0 until 50).toList(), chunks)
            assertEquals("typed meanwhile", conversation.draft)
        }
}
