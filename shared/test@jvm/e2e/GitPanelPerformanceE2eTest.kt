package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CHANGES = 2_000
private const val MAX_COMPOSED_ROWS = 120

/**
 * Performance of the Git panel on [CHANGES] changes at once: opening the panel, and moving a file between the Commit
 * section and the other changes (a tick is a partition of the whole list). The list is virtualized, so the rows
 * composed stay far below the changes listed.
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
            awaitTag(Tags.GIT_OTHER_FILES)

            measure("tick one of $CHANGES changes", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(ItemTags.gitChangeCheck(name(0))).performClick()
                awaitCondition { app.project.selectedForCommit == setOf(name(0)) }
                renderFrame()
            }
            // The list keeps the first visible row on screen; scrolled up, the Commit section is on top again.
            changeList().performScrollToIndex(0)
            awaitTag(Tags.GIT_COMMIT_FILES)
            assertEquals(setOf(name(0)), app.project.selectedForCommit)
            assertTrue(composedCheckboxes() < MAX_COMPOSED_ROWS, "composed checkboxes: ${composedCheckboxes()}")

            measure("untick it again", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(ItemTags.gitChangeCheck(name(0))).performClick()
                awaitCondition { app.project.selectedForCommit.isEmpty() }
                renderFrame()
            }
            changeList().performScrollToIndex(0)
            awaitNoTag(Tags.GIT_COMMIT_FILES)
            awaitTag(Tags.GIT_OTHER_FILES)
        }

    // The lazy column of the changes, the one scrollable node inside the Git panel.
    private fun ComposeUiTest.changeList() =
        onNode(
            hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(Tags.GIT_PANEL)),
        )
}
