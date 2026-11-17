package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FOLDERS = 5_000
private const val MAX_COMPOSED_ROWS = 60
private const val FOLDER_ROWS = "agent.folder.choice:"

/**
 * Performance of the folder of a conversation in a project with [FOLDERS] folders at its root: opening the menu,
 * narrowing the list and choosing one. The list is virtualized, so only the visible rows are composed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentFolderPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // After `src` in the tree, which the launch waits for: the folders do not push it out of the virtualized list.
    private fun name(index: Int) = "zmodule-${index.toString().padStart(4, '0')}"

    @Test
    fun `the menu lists, narrows and chooses fast among thousands of folders, and composes only the visible rows`() =
        runComposeUiTest {
            repeat(FOLDERS) { project.root.resolve(name(it)).createDirectories() }
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            measure("open the menu on $FOLDERS folders", budgetMillis = 10_000, budgetMegabytes = 400) {
                openFolderMenu()
                awaitTag(AgentTags.agentFolderChoice(project.root.resolve(name(0)).toString()), timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix(FOLDER_ROWS)).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
            measure("narrow $FOLDERS folders", budgetMillis = 8_000, budgetMegabytes = 300) {
                typeFolder(name(4_321))
                awaitNoTag(AgentTags.agentFolderChoice(project.root.resolve(name(0)).toString()), timeout = AGENT_TIMEOUT)
                awaitTag(AgentTags.agentFolderChoice(project.root.resolve(name(4_321)).toString()), timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            measure("choose one of $FOLDERS folders", budgetMillis = 5_000, budgetMegabytes = 200) {
                submitFolder()
                awaitCondition(AGENT_TIMEOUT) { conversation.subdirectory == name(4_321) }
                renderFrame()
            }
            assertEquals(name(4_321), conversation.subdirectory)
        }
}
