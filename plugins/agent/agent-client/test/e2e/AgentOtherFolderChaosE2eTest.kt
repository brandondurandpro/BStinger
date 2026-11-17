package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A conversation in another folder of the server, when the link to the server fails. */
@OptIn(ExperimentalTestApi::class)
class AgentOtherFolderChaosE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        project.commitAll()
        other.commitAll()
        val app = launchApp(recent = listOf(project.path, other.path), preferences = agentPreferences(), chaos = true)
        awaitTag(AppTags.recentProject(project.path)).performClick()
        awaitTag(AgentTags.MODE_AGENT).performClick()
        return app
    }

    @Test
    fun `a folder chosen while the network is down is taken once the link is back, by one conversation`() =
        runComposeUiTest {
            val app = open()
            val conversation = clickNewConversationWithoutProject(app)
            openFolderMenu()
            app.network.goDown()
            awaitReconnecting()
            awaitTag(AgentTags.agentFolderProject(other.path)).performClick()
            app.network.comeBack()
            awaitReconnected()
            // The click may have waited for the link or failed: the user chooses again until it is taken.
            awaitCondition(AGENT_TIMEOUT) {
                if (conversation.scratch && !isShown(AgentTags.AGENT_FOLDER_ERROR) && !isShown(AgentTags.AGENT_BASE_PICKER)) Unit
                !conversation.scratch
            }
            assertEquals(1, app.project.agent.conversations.size, "one conversation, however many retries")
            assertEquals(other.path, conversation.folder)
        }

    @Test
    fun `a server that restarts leaves the conversation of the other folder in its group, and it goes on`() =
        runComposeUiTest {
            val app = open()
            val conversation = clickNewConversationWithoutProject(app)
            openFolderMenu()
            awaitTag(AgentTags.agentFolderProject(other.path)).performClick()
            awaitTag(AgentTags.AGENT_BASE_PICKER)
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${other.path}")
            awaitIdle(conversation)
            app.process.restart()
            awaitReconnected()
            awaitTag(AgentTags.agentFolderGroup(other.path))
            assertEquals(other.path, conversation.folder)
            sendPrompt("echo still here")
            awaitAnswer(conversation, "still here", AGENT_TIMEOUT)
            assertTrue(conversation.workspace?.root?.path == other.path)
        }

    @Test
    fun `plus while the server is out of reach starts one conversation once the link is back`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            awaitTag(AgentTags.AGENT_NEW).performClick()
            assertEquals(0, app.project.agent.conversations.size)
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.conversations.isNotEmpty() }
            assertEquals(1, app.project.agent.conversations.size, "one conversation, however many retries")
        }
}
