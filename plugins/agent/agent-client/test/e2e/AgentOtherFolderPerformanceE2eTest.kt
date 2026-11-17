package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SESSIONS = 1_500
private const val MAX_COMPOSED_ROWS = 60

/**
 * Performance of the history when the conversations work in another folder as well: [SESSIONS] sessions of the other
 * folder are listed under its group, the list stays virtualized, and the group folds fast.
 */
@OptIn(ExperimentalTestApi::class)
class AgentOtherFolderPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `thousands of sessions of another folder are listed under its group, folded and unfolded fast`() =
        runComposeUiTest {
            project.commitAll()
            repeat(SESSIONS) { pastSession(store, other.path, listOf("echo session $it" to "session $it ")) }
            val values = agentPreferences("--store", "$store").load() + (PreferenceKeys.AGENT_FOLDERS_PREFIX + project.path to other.path)
            val app = launchWithProject(project, InMemoryPreferenceStore(values))
            awaitTag(AgentTags.MODE_AGENT).performClick()
            measure("list $SESSIONS sessions of another folder", budgetMillis = 60_000, budgetMegabytes = 800) {
                awaitCondition(AGENT_TIMEOUT) { app.project.agent.conversations.count { it.folder == other.path } == SESSIONS }
                awaitTag(AgentTags.agentFolderGroup(other.path))
                renderFrame()
            }
            val rows = AgentTags.agentConversation(0).removeSuffix("0")
            val composed = onAllNodes(hasTestTagPrefix(rows)).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
            measure("fold the group of $SESSIONS sessions", budgetMillis = 5_000, budgetMegabytes = 200) {
                awaitTag(AgentTags.agentFolderGroup(other.path)).performClick()
                renderFrame()
            }
            assertEquals(0, onAllNodes(hasTestTagPrefix(rows)).fetchSemanticsNodes().count { it.config.toString().contains(other.path) })
        }
}
