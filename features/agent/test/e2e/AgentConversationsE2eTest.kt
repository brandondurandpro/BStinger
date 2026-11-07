package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest

import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.TextClipboard
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the user keeps of a conversation: a name of its own (the menu of the list renames it, Enter keeps, Escape
 * cancels), a message of the transcript copied alone or the conversation copied entire in Markdown, and a prompt of
 * past that comes back to the field to be changed and sent again.
 */
@OptIn(ExperimentalTestApi::class)
class AgentConversationsE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `a conversation renamed, keeps its name, and cancelled does not`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val conversation = startConversation(app)
            sendPrompt("echo anchor")
            awaitShown(conversation, "anchor")
            awaitIdle(conversation)
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_RENAME).performClick()
            awaitTag(AgentTags.AGENT_RENAME_FIELD).performTextReplacement("The anchor question")
            awaitTag(AgentTags.AGENT_RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
            // Renamed, and kept when opened again another way.
            awaitCondition(AGENT_TIMEOUT) { textOf(AgentTags.agentConversation(conversation.id)).contains("The anchor question") }
            awaitCondition(AGENT_TIMEOUT) { conversation.title == "The anchor question" }
            // Renaming again and escaping: the name stays.
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_RENAME).performClick()
            awaitTag(AgentTags.AGENT_RENAME_FIELD).performTextReplacement("Not kept")
            awaitTag(AgentTags.AGENT_RENAME_FIELD).performKeyInput { pressKey(Key.Escape) }
            awaitCondition(AGENT_TIMEOUT) { conversation.title == "The anchor question" }
            awaitNoTag(AgentTags.AGENT_RENAME_FIELD)
        }

    @Test
    fun `a message copied is the one the transcript shows`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val conversation = startConversation(app)
            sendPrompt("echo one and two")
            awaitShown(conversation, "one and two")
            awaitIdle(conversation)
            // Hover the message: its actions show; the copy takes the message alone.
            awaitTag(AgentTags.agentMessage(0)).performMouseInput { moveTo(center) }
            val copy = awaitTag(AgentTags.agentCopy(0), timeout = AGENT_TIMEOUT)
            copy.performClick()
            awaitCondition(AGENT_TIMEOUT) { TextClipboard.get() == "## You\necho one and two" }
            // The prompt itself goes back to the field, ready to be changed and sent again.
            awaitTag(AgentTags.agentMessage(0)).performMouseInput { moveTo(center) }
            awaitTag(AgentTags.agentResend(0), timeout = AGENT_TIMEOUT).performClick()
            assertEquals("echo one and two", conversation.draft)
        }

    @Test
    fun `the conversation copied entire is one markdown of its messages`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val conversation = startConversation(app)
            sendPrompt("echo three")
            awaitShown(conversation, "three")
            awaitIdle(conversation)
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_TRANSCRIPT).performClick()
            awaitCondition(AGENT_TIMEOUT) { !TextClipboard.get().isNullOrBlank() }
            val copied = TextClipboard.get().orEmpty()
            assertTrue(
                copied.contains("## You\necho three") && copied.contains("three"),
                "The copy:\n$copied\n\nThe conversation shows:\n${shownConversation(conversation)}",
            )
        }
}
