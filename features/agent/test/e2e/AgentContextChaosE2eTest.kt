package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The context of a prompt when the network, the server or the disk fails: a prompt and what is joined to it go once,
 * a file deleted meanwhile is said to be left out, a slow link does not mix up the files listed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentContextChaosE2eTest {
    private val project = SampleProject.standard()
    private val app = "src/main/kotlin/App.kt"
    private val appLines = SampleProject.APP_KT.trimEnd('\n').lines().size

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Joins App.kt to the prompt with an `@`.
    private fun androidx.compose.ui.test.ComposeUiTest.joinApp() {
        typePrompt("@App.kt")
        awaitTag(AgentTags.agentCompletion(app), timeout = AGENT_TIMEOUT)
        pressInPrompt(Key.Enter)
        awaitTag(AgentTags.agentAttachment("App.kt"))
    }

    @Test
    fun `a prompt with a file, sent while the network is down, goes once with its file when it is back`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(launched)
            joinApp()
            launched.network.goDown()
            awaitReconnecting()
            typePrompt("context")
            pressInPrompt(Key.Enter)
            launched.network.comeBack()
            awaitReconnected()
            awaitShown(conversation, "Context: file App.kt ($appLines lines).")
            awaitIdle(conversation)
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
        }

    @Test
    fun `a prompt whose answer was lost runs once, its context with it`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(launched)
            joinApp()
            typePrompt("context")
            launched.network.dropFromServer = true
            pressInPrompt(Key.Enter)
            launched.network.cutAll()
            launched.network.dropFromServer = false
            awaitReconnected()
            awaitShown(conversation, "Context: file App.kt ($appLines lines).")
            awaitIdle(conversation)
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
            assertEquals(listOf("App.kt"), conversation.itemsOf<AgentItem.UserText>().single().context)
        }

    @Test
    fun `a file deleted after it was joined is said to be left out, and the prompt goes`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(launched)
            joinApp()
            Files.delete(project.root.resolve(app))
            typePrompt("context")
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "App.kt was not sent: the file no longer exists.")
            awaitShown(conversation, "Context: none.")
            awaitIdle(conversation)
        }

    @Test
    fun `on a slow link the files listed are those of the word typed last, and the prompt stays responsive`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(launched)
            launched.network.latencyMs = 300L
            typePrompt("@Str")
            typePrompt("ings")
            awaitTag(AgentTags.agentCompletion("src/main/kotlin/util/Strings.kt"), timeout = AGENT_TIMEOUT)
            assertEquals(listOf("src/main/kotlin/util/Strings.kt"), suggestions())
            assertEquals("@Strings", conversation.draft)
            onNodeWithTag(AgentTags.agentCompletion("src/main/kotlin/util/Strings.kt")).performClick()
            awaitTag(AgentTags.agentAttachment("Strings.kt"))
            typePrompt("context")
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Context: file Strings.kt", timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
            launched.network.latencyMs = 0L
        }
}
