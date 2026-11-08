package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Up and Down in the empty prompt walk through the prompts already sent, like a shell history. */
@OptIn(ExperimentalTestApi::class)
class AgentPromptHistoryE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `up brings back the previous prompts and down goes forward again`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo first-prompt")
            awaitShown(conversation, "first-prompt")
            awaitIdle(conversation)
            sendPrompt("echo second-prompt")
            awaitShown(conversation, "second-prompt")
            awaitIdle(conversation)
            val prompt = awaitTag(AgentTags.AGENT_PROMPT)
            prompt.performKeyInput { pressKey(Key.DirectionUp) }
            awaitCondition { conversation.draft == "echo second-prompt" }
            prompt.performKeyInput { pressKey(Key.DirectionUp) }
            awaitCondition { conversation.draft == "echo first-prompt" }
            prompt.performKeyInput { pressKey(Key.DirectionDown) }
            awaitCondition { conversation.draft == "echo second-prompt" }
            prompt.performKeyInput { pressKey(Key.DirectionDown) }
            awaitCondition { conversation.draft.isEmpty() }
            assertEquals("", conversation.draft)
        }
}
