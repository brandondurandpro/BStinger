package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A conversation released with its tab while the network or the server fails: it opens again with its messages, once
 * each, whether the server heard that it was closed or not.
 */
@OptIn(ExperimentalTestApi::class)
class AgentReleaseChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    @Test
    fun `a tab closed while the network is down opens again with its messages once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo before the cut")
            awaitAnswer(conversation, "before the cut")
            awaitIdle(conversation)
            app.network.goDown()
            awaitReconnecting()
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            assertTrue(conversation.items.isEmpty())
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            app.network.comeBack()
            awaitReconnected()
            awaitAnswer(conversation, "before the cut")
            awaitIdle(conversation)
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
        }

    @Test
    fun `a tab closed whose answer was lost opens again with its messages once`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo answer lost")
            awaitAnswer(conversation, "answer lost")
            awaitIdle(conversation)
            // The server closes the conversation, but the IDE never hears it did.
            app.network.dropFromServer = true
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "answer lost")
            awaitIdle(conversation)
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
        }

    @Test
    fun `a tab closed before the server restarts opens again with its messages`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo before the restart")
            awaitAnswer(conversation, "before the restart")
            awaitIdle(conversation)
            awaitTag(AgentTags.agentTabClose(conversation.id)).performClick()
            awaitNoTag(AgentTags.agentTab(conversation.id))
            app.process.restart()
            awaitReconnected()
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "before the restart")
            awaitIdle(conversation)
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
        }
}
