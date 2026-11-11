package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeRegistry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The agent of a conversation when the network or the server fails: the choice withholds nothing, a prompt of the
 * agent chosen is sent once the link is back, and the conversation goes on in the session the agent keeps.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPickerChaosE2eTest {
    private val project = SampleProject.standard()
    private val registry = FakeRegistry(registryPlatformForTests())
    private val data = Files.createTempDirectory("agent-data")

    @AfterTest
    fun cleanup() {
        project.delete()
        registry.close()
        data.toFile().deleteRecursively()
    }

    @Test
    fun `a prompt of the agent chosen, sent while the network is down, is sent once it is back`() =
        // A slow runner reconnects and restarts the prompt in more than the default minute.
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(
                project,
                agentPreferences(),
                chaos = true,
                agents = agentSettings(registry.url, data.toFile()),
            )
            val conversation = startConversation(app)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
            chooseAgent("fake-agent")
            // Sent while the link is out: shown at once, taken into account once the IDE is reconnected.
            sendPrompt("count")
            awaitShown(conversation, "no longer remembers what the previous agent")
            app.network.goDown()
            awaitReconnecting()
            sendPrompt("count")
            app.network.comeBack()
            awaitReconnected()
            // The session of the chosen agent was already running: the prompt goes on counting in it.
            awaitShown(conversation, "Prompt 2 of")
            awaitIdle(conversation)
            assertEquals(3, conversation.itemsOf<AgentItem.UserText>().size)
        }

    @Test
    fun `after the server restarts the conversation goes on in the session the chosen agent kept`() =
        runComposeUiTest {
            val app = launchWithProject(
                project,
                agentPreferences(),
                chaos = true,
                agents = agentSettings(registry.url, data.toFile()),
            )
            val conversation = startConversation(app)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
            chooseAgent("fake-agent")
            sendPrompt("count")
            awaitShown(conversation, "no longer remembers what the previous agent")
            awaitIdle(conversation)
            app.process.restart()
            awaitReconnected()
            sendPrompt("count")
            awaitIdle(conversation)
            // The prompts all stand, none twice; the answer came from the agent chosen.
            assertEquals(3, conversation.itemsOf<AgentItem.UserText>().size)
        }
}
