package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val BRANCHES = 3_000
private const val SCROLL_STEPS = 60
private const val MAX_COMPOSED_ROWS = 60

/**
 * Performance of the branch picker on a repository with [BRANCHES] local branches: opening it, scrolling it and
 * filtering it. The list is virtualized, so only the visible rows are composed; each step has a budget so a linear
 * scan per frame or a complete rebuild of the list fails the test.
 */
@OptIn(ExperimentalTestApi::class)
class BranchPickerPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun createBranches() {
        val head = project.git("rev-parse", "HEAD").trim()
        repeat(BRANCHES) { index ->
            val name = "monitored/branch-${index.toString().padStart(4, '0')}"
            val ref = project.root.resolve(".git/refs/heads/$name")
            ref.parent.createDirectories()
            ref.writeText("$head\n")
        }
    }

    private fun ComposeUiTest.composedRows() = onAllNodes(hasTestTagPrefix("branch.item:")).fetchSemanticsNodes().size

    @Test
    fun `a repository with thousands of branches`() =
        runComposeUiTest {
            project.commitAll()
            createBranches()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.size > BRANCHES }

            measure("open the branch picker with $BRANCHES branches", budgetMillis = 10_000, budgetMegabytes = 100) {
                onNodeWithTag(Tags.GIT_BRANCH).performClick()
                awaitTag(Tags.BRANCH_PICKER)
                awaitTag(ItemTags.branchItem("monitored/branch-0000"))
                renderFrame()
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed branch rows: ${composedRows()}")

            val list = onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(Tags.BRANCH_PICKER)))
            measure(
                "scroll the branch picker through $SCROLL_STEPS positions",
                budgetMillis = 15_000,
                budgetMegabytes = 200,
            ) {
                repeat(SCROLL_STEPS) { step ->
                    list.performScrollToIndex(1 + step * (BRANCHES / SCROLL_STEPS))
                    renderFrame()
                }
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed branch rows after scrolling: ${composedRows()}")

            measure("search one branch among $BRANCHES", budgetMillis = 5_000, budgetMegabytes = 50) {
                onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("branch-2999")
                awaitTag(ItemTags.branchItem("monitored/branch-2999"))
                renderFrame()
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed branch rows after search: ${composedRows()}")
        }
}
