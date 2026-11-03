package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model and the mode of permissions of a conversation when the network or the server fails: a choice is set once the server is back, a
 * restart forgets none, and on a slow link the last choice wins.
 */
@OptIn(ExperimentalTestApi::class)
class AgentModelChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    @Test
    fun `a model chosen while the network is down is set once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo warm up")
            awaitIdle(conversation)
            app.network.goDown()
            awaitReconnecting()
            chooseModel("fake-smart")
            // Shown at once, set when the server is back.
            awaitModel("Smart")
            app.network.comeBack()
            awaitReconnected()
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-smart.")
            awaitIdle(conversation)
            awaitModel("Smart")
            assertTrue(conversation.itemsOf<AgentItem.Notice>().isEmpty(), "No notice: ${conversation.items}")
        }

    @Test
    fun `a model chosen before the first prompt survives a restart of the server`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            chooseModel("fake-max")
            awaitModel("Max")
            app.process.restart()
            awaitReconnected()
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-max.")
            awaitIdle(conversation)
            awaitModel("Max")
        }

    @Test
    fun `the model of a session survives a restart of the server`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo first")
            awaitIdle(conversation)
            chooseModel("fake-smart")
            awaitModel("Smart")
            app.process.restart()
            awaitReconnected()
            sendPrompt("model")
            // The agent kept the model of its session, which the server reopened.
            awaitShown(conversation, "Model: fake-smart.")
            awaitIdle(conversation)
            awaitModel("Smart")
        }

    @Test
    fun `on a slow link the last model chosen wins`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            sendPrompt("echo first")
            awaitIdle(conversation)
            app.network.latencyMs = 400
            chooseModel("fake-smart")
            chooseModel("fake-max")
            awaitModel("Max")
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-max.", timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
            app.network.latencyMs = 0
            awaitModel("Max")
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().count { it.text.startsWith("Model:") })
        }

    @Test
    fun `Accept edits chosen while the network is down applies once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            val conversation = startConversation(app)
            awaitPermissions("Manual")
            app.network.goDown()
            awaitReconnecting()
            choosePermissions("accept-edits")
            awaitPermissions("Accept edits")
            app.network.comeBack()
            awaitReconnected()
            val file = project.root.resolve("OFFLINE.md")
            sendPrompt("write $file offline")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertEquals("offline", file.readText())
            assertTrue(conversation.itemsOf<AgentItem.Permission>().isEmpty(), "Asked: ${conversation.items}")
        }
}
