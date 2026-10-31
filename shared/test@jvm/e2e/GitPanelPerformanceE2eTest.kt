package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CHANGES = 2_000
private const val MAX_COMPOSED_ROWS = 120

/**
 * Performance of the Git panel on [CHANGES] changes at once: opening the panel and ticking a file for the next
 * commit (a state read per row). The list is virtualized, so the rows composed stay far below the changes listed.
 */
@OptIn(ExperimentalTestApi::class)
class GitPanelPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun name(i: Int) = "huge/change${i.toString().padStart(4, '0')}.txt"

    private fun ComposeUiTest.composedChangeRows() =
        onAllNodes(hasTestTagPrefix("git.change:")).fetchSemanticsNodes().size

    private fun ComposeUiTest.composedCheckboxes() =
        onAllNodes(hasTestTagPrefix("git.change.check:")).fetchSemanticsNodes().size

    @Test
    fun `the Git panel opens, splits and ticks two thousand changes in time`() =
        runComposeUiTest {
            val files = (0 until CHANGES).map { name(it) }
            files.forEach { project.file(it, "one\n") }
            project.commitAll()
            // After the commit, every file of the directory is modified: the status lists all of them.
            files.forEach { project.file(it, "two\n") }
            val app = launchWithProject(project)
            awaitCondition { app.project.gitStatus.size == CHANGES }

            measure("open the Git panel on $CHANGES changes", budgetMillis = 8_000, budgetMegabytes = 200) {
                onNodeWithTag(Tags.TOOL_GIT).performClick()
                awaitTag(ItemTags.gitChangeCheck(name(0)))
                renderFrame()
            }
            assertTrue(composedChangeRows() < MAX_COMPOSED_ROWS, "composed rows: ${composedChangeRows()}")

            // A tick is read per row: only the ticked row recomposes, not the list.
            measure("tick one of $CHANGES changes", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(ItemTags.gitChangeCheck(name(0))).performClick()
                awaitCondition { app.project.selectedForCommit == setOf(name(0)) }
                renderFrame()
            }
            assertTrue(composedCheckboxes() < MAX_COMPOSED_ROWS, "composed checkboxes: ${composedCheckboxes()}")

            measure("untick it again", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(ItemTags.gitChangeCheck(name(0))).performClick()
                awaitCondition { app.project.selectedForCommit.isEmpty() }
                renderFrame()
            }

            measure("tick all of $CHANGES changes at once", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(Tags.GIT_SELECT_ALL).performClick()
                awaitCondition { app.project.selectedForCommit.size == CHANGES }
                renderFrame()
            }
        }
}
