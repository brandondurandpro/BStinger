package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val NESTED = 200

/** The popup of a sub-agent that made hundreds of calls. */
@OptIn(ExperimentalTestApi::class)
class AgentStripPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `the popup of a sub-agent with 200 calls opens quickly and composes only the cards in view`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val conversation = startConversation(app)
            sendPrompt("subagent-calls $NESTED Dev: big job")
            awaitIdle(conversation)
            awaitTag(AgentTags.agentStripEntry("Dev: big job"), timeout = AGENT_TIMEOUT)
            measure("open the popup of a sub-agent with $NESTED calls", budgetMillis = 8_000, budgetMegabytes = 500) {
                awaitTag(AgentTags.agentStripEntry("Dev: big job")).performClick()
                awaitTag(AgentTags.AGENT_STRIP_DETAIL)
                renderFrame()
            }
            val composed = onAllNodes(hasText("Output of", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(composed < NESTED, "composed cards: $composed of $NESTED")
        }
}
