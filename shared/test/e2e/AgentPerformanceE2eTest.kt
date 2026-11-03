package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A long answer streamed chunk by chunk, and a long transcript of tool calls.
private const val CHUNKS = 5_000
private const val TOOL_CALLS = 2_000
private const val HISTORY_TURNS = 2_000
private const val COMMAND_LINES = 200_000
private const val STEPS = 40
private const val TYPED = "a prompt typed key by key"

// Measured here: 250 ms to stream, 185 ms for the tool calls, 730 ms to scroll, 450 ms to jump, 210 ms to type,
// 130 ms to replay the history.
// The budgets leave room for a slow runner, not for a cost per item or per chunk.
private const val STREAM_BUDGET_MS = 5_000L
private const val TOOLS_BUDGET_MS = 5_000L
private const val SCROLL_BUDGET_MS = 4_000L
private const val JUMPS_BUDGET_MS = 3_000L
private const val TYPING_BUDGET_MS = 2_000L
private const val HISTORY_BUDGET_MS = 3_000L
private const val COMMAND_BUDGET_MS = 10_000L
private const val REGISTRY_AGENTS = 2_000
private const val REGISTRY_BUDGET_MS = 3_000L
private const val BUDGET_MB = 800L
private const val MAX_COMPOSED_ROWS = 80
private const val MAX_SHOWN_OUTPUT = 16_000

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
    fun `a command of thousands of lines shows its end fast`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo ready")
            awaitIdle(conversation)
            measure("Command of $COMMAND_LINES lines run and shown", COMMAND_BUDGET_MS, BUDGET_MB) {
                sendPrompt("run $COMMAND_LINES 0")
                awaitCondition(COMMAND_BUDGET_MS) { "line $COMMAND_LINES" in textOf(AppTags.AGENT_TERMINAL) }
                awaitIdle(conversation)
                renderFrame()
            }
            // The transcript carries the end of the output, never all of it.
            val shown = conversation.itemsOf<AgentItem.ToolCall>().lastOrNull()?.terminal
            assertTrue(shown?.truncated == true)
            assertTrue(shown.text.length <= MAX_SHOWN_OUTPUT, "${shown.text.length} characters shown")
        }

    @Test
    fun `a registry of thousands of agents lists fast, virtualized`() =
        runComposeUiTest {
            val registry = FakeRegistry(registryPlatformForTests(), extraAgents = REGISTRY_AGENTS)
            val data = Files.createTempDirectory("agent-data").toFile()
            val app = launchWithProject(project, agentPreferences(), agents = agentSettings(registry.url, data))
            app.state.openSettings()
            measure("Registry of $REGISTRY_AGENTS agents listed", REGISTRY_BUDGET_MS, BUDGET_MB) {
                awaitTag(AppTags.settingsCategory("Experimental")).performClick()
                awaitTag(AppTags.settingsAgent("fake-agent"), timeout = REGISTRY_BUDGET_MS)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix("settings.agent:")).fetchSemanticsNodes().size
            assertTrue(composed in 1..MAX_COMPOSED_ROWS, "$composed rows composed: the list must be virtualized")
            registry.close()
            data.deleteRecursively()
        }

    @Test
    fun `a long history of the agent replays fast`() =
        runComposeUiTest {
            val store = Files.createTempDirectory("agent-store")
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val turns = List(HISTORY_TURNS) { "echo prompt $it" to "answer $it " }
            val session = pastSession(store, app.project.root.path, turns)
            // The agent process starts, and lists its sessions, outside the measure.
            val conversation = awaitHistory(app, session)
            measure("History of $HISTORY_TURNS prompts replayed", HISTORY_BUDGET_MS, BUDGET_MB) {
                awaitTag(AppTags.agentConversation(conversation.id)).performClick()
                awaitCondition(HISTORY_BUDGET_MS) { conversation.items.size == 2 * HISTORY_TURNS }
                awaitIdle(conversation)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix("agent.message.")).fetchSemanticsNodes().size
            assertTrue(composed in 1..MAX_COMPOSED_ROWS, "$composed rows composed: the list must be virtualized")
            store.toFile().deleteRecursively()
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
