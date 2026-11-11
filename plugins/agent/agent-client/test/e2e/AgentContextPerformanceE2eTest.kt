package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FILES = 5_000
private const val PER_DIRECTORY = 100
private const val LAST = "File${FILES - 1}.kt"

// Measured here: 105 ms to list, 140 ms to filter, 90 ms to send. The budgets leave room for a slow runner, not for
// a cost per file per key.
private const val MENTION_BUDGET_MS = 2_000L
private const val FILTER_BUDGET_MS = 2_000L
private const val SEND_BUDGET_MS = 3_000L
private const val BUDGET_MB = 800L
private const val MAX_COMPOSED_SUGGESTIONS = 50

/**
 * The `@` mentions of the prompt in a project of 5,000 files: the list shows fast and stays short, filters key by key,
 * and a prompt goes with the file chosen.
 */
@OptIn(ExperimentalTestApi::class)
class AgentContextPerformanceE2eTest {
    private val project = SampleProject.standard()

    init {
        repeat(FILES) { index ->
            project.file("gen/d${index / PER_DIRECTORY}/File$index.kt", "class File$index\n")
        }
    }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `an @ lists thousands of files fast, a few rows composed, and filters key by key`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            measure("@ typed over $FILES files", MENTION_BUDGET_MS, BUDGET_MB) {
                typePrompt("context @")
                awaitTag(AgentTags.AGENT_COMPLETION, timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix(AgentTags.agentCompletion(""))).fetchSemanticsNodes().size
            assertTrue(composed in 1..MAX_COMPOSED_SUGGESTIONS, "$composed suggestions composed")
            val path = "gen/d${(FILES - 1) / PER_DIRECTORY}/$LAST"
            measure("Files filtered key by key", FILTER_BUDGET_MS, BUDGET_MB) {
                for (key in LAST.removeSuffix(".kt")) typePrompt("$key")
                awaitCondition { suggestions().firstOrNull() == path }
                renderFrame()
            }
            measure("Prompt sent with the file chosen", SEND_BUDGET_MS, BUDGET_MB) {
                pressInPrompt(Key.Enter)
                awaitTag(AgentTags.agentAttachment(LAST))
                pressInPrompt(Key.Enter)
                awaitShown(conversation, "Context: file $LAST (1 lines).")
                renderFrame()
            }
            awaitIdle(conversation)
            assertEquals(emptyList(), attachments())
        }
}
