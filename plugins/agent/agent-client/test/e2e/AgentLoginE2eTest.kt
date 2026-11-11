package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** An agent that asks to log in first: the conversation offers its methods, and the prompt works once logged in. */
@OptIn(ExperimentalTestApi::class)
class AgentLoginE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `logging in from the conversation lets the next prompt through`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--login"))
            val conversation = startConversation(app)
            sendPrompt("echo hi")
            // The agent refused the session: the notice and a button for the method it listed.
            awaitShown(conversation, "Log in to the agent first")
            awaitTag(AgentTags.AGENT_LOGIN)
            onNodeWithText("Fake login").performClick()
            awaitShown(conversation, "Logged in with Fake login")
            sendPrompt("echo hi")
            awaitAnswer(conversation, "hi")
            awaitIdle(conversation)
        }
}
