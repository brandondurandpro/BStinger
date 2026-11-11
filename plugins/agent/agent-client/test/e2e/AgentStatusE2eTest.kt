package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** The status of a conversation in the list: asking while a permission waits, then seen once the turn ended while the user looks. */
@OptIn(ExperimentalTestApi::class)
class AgentStatusE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the list shows when the agent waits for an answer, then when it is done and seen`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("STATUS.md")
            sendPrompt("write $file status")
            awaitShown(conversation, "The agent asks to: Write")
            awaitTag(AgentTags.agentAsking(conversation.id))
            answerPermission("allow")
            awaitIdle(conversation)
            awaitNoTag(AgentTags.agentAsking(conversation.id))
            // The user is looking at the conversation: the turn that ended shows as seen.
            awaitTag(AgentTags.agentSeen(conversation.id))
        }
}
