package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A long answer streamed chunk by chunk, and a long transcript of tool calls.
private const val CHUNKS = 5_000
private const val TOOL_CALLS = 2_000
private const val STEPS = 40
private const val TYPED = "a prompt typed key by key"

// Measured here: 250 ms to stream, 185 ms for the tool calls, 730 ms to scroll, 450 ms to jump, 210 ms to type.
// The budgets leave room for a slow runner, not for a cost per item or per chunk.
private const val STREAM_BUDGET_MS = 5_000L
private const val TOOLS_BUDGET_MS = 5_000L
private const val SCROLL_BUDGET_MS = 4_000L
private const val JUMPS_BUDGET_MS = 3_000L
private const val TYPING_BUDGET_MS = 2_000L
private const val BUDGET_MB = 800L
private const val MAX_COMPOSED_ROWS = 80

/**
 * Agent mode on large inputs: an answer of 5,000 chunks, a transcript of 2,000 tool calls scrolled and jumped
 * through, and a prompt typed over it, within budgets that a cost per chunk or per item breaks.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a long answer streams in fast, into one message`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            // The agent process starts outside the measure.
            sendPrompt("echo ready")
            awaitIdle(conversation)
            measure("Answer of $CHUNKS chunks streamed", STREAM_BUDGET_MS, BUDGET_MB) {
                sendPrompt("stream $CHUNKS")
                awaitCondition(STREAM_BUDGET_MS) { "chunk ${CHUNKS - 1} " in lastAnswer(conversation) }
                awaitIdle(conversation)
                renderFrame()
            }
            assertEquals(2, conversation.itemsOf<AgentItem.AgentText>().size)
        }

    @Test
    fun `a transcript of thousands of tool calls scrolls, jumps and takes a prompt fast`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo ready")
            awaitIdle(conversation)
            measure("Transcript of $TOOL_CALLS tool calls shown", TOOLS_BUDGET_MS, BUDGET_MB) {
                sendPrompt("tools $TOOL_CALLS")
                awaitCondition(TOOLS_BUDGET_MS) { conversation.itemsOf<AgentItem.ToolCall>().size == TOOL_CALLS }
                awaitIdle(conversation)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix("agent.message.")).fetchSemanticsNodes().size
            assertTrue(composed in 1..MAX_COMPOSED_ROWS, "$composed rows composed: the list must be virtualized")
            val list = conversation.listState
            val still = costOf { repeat(STEPS) { renderFrame() } }
            val scrolled =
                costOf {
                    repeat(STEPS) {
                        runBlocking { list.scrollToItem((list.firstVisibleItemIndex - 25).coerceAtLeast(0)) }
                        renderFrame()
                    }
                }
            report("Transcript scrolled by 25 items x$STEPS (net)", scrolled - still, SCROLL_BUDGET_MS, BUDGET_MB)
            measure("Transcript jumped top and bottom x$STEPS", JUMPS_BUDGET_MS, BUDGET_MB) {
                repeat(STEPS) { step ->
                    val target = if (step % 2 == 0) 0 else conversation.items.lastIndex
                    runBlocking { list.scrollToItem(target) }
                    renderFrame()
                }
            }
            measure("Prompt typed over the transcript", TYPING_BUDGET_MS, BUDGET_MB) {
                for (letter in TYPED) {
                    onNodeWithTag(AppTags.AGENT_PROMPT).performTextInput(letter.toString())
                    renderFrame()
                }
            }
            assertEquals(TYPED, conversation.draft)
            assertTrue(onAllNodes(hasTestTag(AppTags.AGENT_SEND)).fetchSemanticsNodes().isNotEmpty())
        }

    private fun lastAnswer(conversation: org.example.kotlineditor.state.Conversation): String =
        conversation
            .itemsOf<AgentItem.AgentText>()
            .lastOrNull()
            ?.text
            .orEmpty()
}
