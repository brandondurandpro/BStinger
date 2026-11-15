package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The IDE's relay when the network or the server fails: a delegation ends, is followed again, or answers once. */
@OptIn(ExperimentalTestApi::class)
class AgentRelayChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.startRelay(): Pair<E2eApp, Conversation> {
        val app = launchWithProject(project, agentPreferences("--store", "$store"), chaos = true)
        val conversation = startConversation(app)
        app.project.agent.saveTeam(null, relayTeam())
        awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
        awaitTag(AgentTags.agentTeam("Crew")).performClick()
        sendPrompt("hello")
        awaitShown(conversation, "You said")
        awaitIdle(conversation)
        return app to conversation
    }

    private fun ComposeUiTest.openDev() {
        awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT).performClick()
        awaitTag(AgentTags.AGENT_STRIP_DETAIL)
    }

    @Test
    fun `a server restarted during a delegation leaves nothing running, and the conversation goes on`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = startRelay()
            sendPrompt("delegate-via-mcp Dev slow")
            awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT)
            app.process.restart()
            awaitReconnected()
            awaitCondition(AGENT_TIMEOUT) { !conversation.busy }
            val calls = conversation.itemsOf<AgentItem.ToolCall>()
            assertTrue(calls.none { it.status == AgentToolStatus.Running }, "still running: $calls")
            sendPrompt("echo after the restart")
            awaitShown(conversation, "after the restart")
            awaitIdle(conversation)
        }

    @Test
    fun `a popup open while the network is down shows the report once it is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = startRelay()
            sendPrompt("delegate-via-mcp Dev echo hi")
            openDev()
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitReconnected()
            awaitIdle(conversation)
            awaitCondition(AGENT_TIMEOUT) { "hi" in textOf(AgentTags.AGENT_STRIP_DETAIL).substringAfter("Report") }
        }

    @Test
    fun `a permission of a member answered while its answer was lost is applied once`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = startRelay()
            val file = project.root.resolve("relay-once.txt")
            sendPrompt("delegate-via-mcp Dev write $file once")
            openDev()
            awaitTag(AgentTags.agentPermission("allow"), timeout = AGENT_TIMEOUT)
            app.network.dropFromServer = true
            onNodeWithTag(AgentTags.agentPermission("allow")).performClick()
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitIdle(conversation)
            awaitCondition(AGENT_TIMEOUT) { Files.exists(file) }
            assertEquals("once", file.readText())
            val delegation = app.project.agent.delegations.values.single()
            assertEquals(1, delegation.itemsOf<AgentItem.Permission>().size)
            assertEquals("allow", delegation.itemsOf<AgentItem.Permission>().single().chosen)
        }
}
