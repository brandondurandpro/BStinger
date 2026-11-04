package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The slash commands when the network or the server fails: the IDE keeps those it knows, and a command runs once. */
@OptIn(ExperimentalTestApi::class)
class AgentSlashCommandsChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `the commands are listed while the network is down, and the command runs once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo warm up")
            awaitIdle(conversation)
            // The commands come with an update of the session, which may land after the answer: known before the cut.
            awaitCondition(AGENT_TIMEOUT) { conversation.commands.any { it.name == "count" } }
            app.network.goDown()
            awaitReconnecting()
            typePrompt("/cou")
            awaitTag(AgentTags.agentCompletion("/count"))
            pressInPrompt(Key.Enter)
            pressInPrompt(Key.Enter)
            app.network.comeBack()
            awaitReconnected()
            awaitShown(conversation, "Prompt 2 of")
            awaitIdle(conversation)
            assertEquals(2, conversation.itemsOf<org.example.kotlineditor.model.AgentItem.UserText>().size)
        }

    @Test
    fun `after a restart of the server the commands still complete, in the session the agent kept`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("/count")
            awaitShown(conversation, "Prompt 1 of")
            awaitIdle(conversation)
            app.process.restart()
            awaitReconnected()
            typePrompt("/c")
            awaitTag(AgentTags.agentCompletion("/count"), timeout = AGENT_TIMEOUT)
            pressInPrompt(Key.Enter)
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Prompt 2 of")
            awaitIdle(conversation)
        }
}
