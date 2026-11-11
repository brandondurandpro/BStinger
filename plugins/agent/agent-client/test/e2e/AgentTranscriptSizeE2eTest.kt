package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What the server keeps of the output of a tool call and of the lines of a change.
private const val KEPT_OUTPUT = 4_000
private const val KEPT_LINES = 40

// Lines of a change a card shows.
private const val SHOWN_LINES = 12

private const val OUTPUT_LINES = 2_000
private const val WRITTEN_LINES = 500

/**
 * A conversation holds what it shows of its tool calls, not all the agent read and wrote: a transcript stays in
 * memory, on the server and in the IDE, for as long as its conversation lasts.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTranscriptSizeE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a tool call with a long output shows its beginning, and holds no more`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("tools 2 $OUTPUT_LINES")
            awaitShown(conversation, "Searched 2 times.")
            awaitIdle(conversation)
            assertContains(shownConversation(conversation), "1 matches")
            assertContains(shownConversation(conversation), "line 1 of the output")
            val outputs = conversation.itemsOf<AgentItem.ToolCall>().map { it.output }
            assertEquals(2, outputs.size)
            val longest = outputs.maxOf { it.length }
            assertTrue(longest <= KEPT_OUTPUT, "an output of $longest characters is held")
        }

    @Test
    fun `a tool call with a short output shows all of it`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("tools 1 2")
            awaitShown(conversation, "Searched 1 times.")
            awaitIdle(conversation)
            val output = conversation.itemsOf<AgentItem.ToolCall>().firstOrNull()?.output
            assertEquals(3, output?.lines()?.size)
            assertContains(shownConversation(conversation), "line 2 of the output")
        }

    @Test
    fun `a long file written shows its first lines and counts the others, all of them on disk`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("LONG.md")
            sendPrompt("lines $WRITTEN_LINES $file")
            answerPermission("allow")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertEquals(WRITTEN_LINES, file.readText().lines().size)
            val shown = textOf(AgentTags.AGENT_DIFF)
            assertContains(shown, "+ line 1")
            assertContains(shown, "+ line $SHOWN_LINES")
            assertEquals("… ${WRITTEN_LINES - SHOWN_LINES} more lines", textOf(AgentTags.AGENT_DIFF_MORE))
            val diff = conversation.itemsOf<AgentItem.ToolCall>().firstOrNull()?.diffs?.firstOrNull()
            assertEquals(KEPT_LINES, diff?.added?.size)
            assertEquals(WRITTEN_LINES - KEPT_LINES, diff?.hiddenLines)
        }

    @Test
    fun `a short file written shows whole, with nothing more to count`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("SHORT.md")
            sendPrompt("lines 3 $file")
            answerPermission("allow")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertContains(textOf(AgentTags.AGENT_DIFF), "+ line 3")
            awaitNoTag(AgentTags.AGENT_DIFF_MORE)
        }
}
