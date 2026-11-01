package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BRANCHES = 3_000
private const val SCROLL_STEPS = 60
private const val WHEEL_STEPS = 40
private const val WHEEL_DELTA = 100f
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

    private fun ComposeUiTest.visibleBranchTags(): List<String> =
        onAllNodes(hasTestTagPrefix("branch.item:"))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }

    // A wheel scroll keeps the list moving frame after frame, unlike jumping to an index. Measured net of the same
    // input plumbing and frames without movement, as the editor's scroll benchmark does.
    private fun ComposeUiTest.measureWheel(list: SemanticsNodeInteraction) {
        list.performScrollToIndex(BRANCHES / 2)
        renderFrame()
        val before = visibleBranchTags()
        repeat(5) {
            list.performMouseInput {
                moveTo(center)
                scroll(WHEEL_DELTA)
            }
            renderFrame()
        }
        assertTrue(visibleBranchTags() != before, "the wheel must move the list")
        list.performScrollToIndex(BRANCHES / 2)
        renderFrame()
        val idle =
            costOf {
                repeat(WHEEL_STEPS) {
                    list.performMouseInput {
                        moveTo(center)
                        scroll(0f)
                    }
                    renderFrame()
                }
            }
        val wheel =
            costOf {
                repeat(WHEEL_STEPS) { step ->
                    // Alternate directions so the list stays in the middle, away from its ends.
                    list.performMouseInput {
                        moveTo(center)
                        scroll(if (step / 10 % 2 == 0) WHEEL_DELTA else -WHEEL_DELTA)
                    }
                    renderFrame()
                }
            }
        report(
            "scroll it with the wheel ($WHEEL_STEPS frames)",
            wheel - idle,
            budgetMillis = 12_000,
            budgetMegabytes = 300,
        )
    }

    private fun localBranchCount(): Int =
        project.git("for-each-ref", "--format=%(refname:short)", "refs/heads").lines().count { it.isNotBlank() }

    private fun ComposeUiTest.composedDeletionRows() =
        onAllNodes(hasTestTagPrefix("branch.toDelete:")).fetchSemanticsNodes().size

    @Test
    fun `bulk actions on thousands of branches`() =
        runComposeUiTest {
            project.commitAll()
            createBranches()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.size > BRANCHES }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            awaitTag(ItemTags.branchItem("monitored/branch-0000"))

            measure("tick all $BRANCHES branches", budgetMillis = 3_000, budgetMegabytes = 100) {
                onNodeWithTag(Tags.BRANCH_SELECT_ALL).performClick()
                awaitTag(Tags.BRANCH_PICK_COUNT)
                renderFrame()
            }
            measure("pin $BRANCHES ticked branches in the toolbar", budgetMillis = 5_000, budgetMegabytes = 200) {
                onNodeWithTag(Tags.BRANCH_PIN_PICKED).performClick()
                awaitNoTag(Tags.BRANCH_PICK_COUNT)
                renderFrame()
            }
            // Select all ticked the current branch too.
            assertEquals(BRANCHES + 1, app.project.selectedBranches.size)
            val chips = onAllNodes(hasTestTagPrefix("branch.pinned:")).fetchSemanticsNodes().size
            assertTrue(chips < MAX_COMPOSED_ROWS, "composed toolbar chips: $chips")

            measure("open the cleanup of $BRANCHES branches", budgetMillis = 3_000, budgetMegabytes = 100) {
                onNodeWithTag(Tags.BRANCH_CLEANUP).performClick()
                awaitTag(ItemTags.branchToDelete("monitored/branch-0000"))
                renderFrame()
            }
            assertTrue(composedDeletionRows() < MAX_COMPOSED_ROWS, "composed rows to delete: ${composedDeletionRows()}")

            // Git's own work dominates: 0.6 s on Linux, 14 s on the Windows runners (a lock file and a config rewrite
            // per branch on a slow disk). The frames rendered meanwhile allocate in proportion to that time, so this
            // step has no memory budget; the UI's own cost is measured by the steps above.
            measure("delete $BRANCHES branches", budgetMillis = 45_000) {
                onNodeWithTag(Tags.BRANCH_DELETE_CONFIRM).performClick()
                awaitTag(Tags.BRANCH_DONE, timeout = 60_000)
                renderFrame()
            }
            assertTrue(localBranchCount() == 1, "branches left: ${localBranchCount()}")
            // Only the current branch, which cannot be deleted, stays pinned.
            awaitCondition { app.project.selectedBranches.size == 1 }
        }

    @Test
    fun `the menu and a push of a pinned branch with thousands of branches pinned`() =
        runComposeUiTest {
            project.commitAll()
            createBranches()
            val remote = Files.createTempDirectory("kotlin-editor-remote")
            try {
                gitIn(remote, "init", "-q", "--bare")
                project.git("remote", "add", "origin", remote.toString())
                val app = launchWithProject(project)
                awaitCondition { app.project.gitBranchInfos.size > BRANCHES }
                onNodeWithTag(Tags.GIT_BRANCH).performClick()
                awaitTag(Tags.BRANCH_PICKER)
                onNodeWithTag(Tags.BRANCH_SELECT_ALL).performClick()
                onNodeWithTag(Tags.BRANCH_PIN_PICKED).performClick()
                awaitNoTag(Tags.BRANCH_PICK_COUNT)
                onNodeWithTag(Tags.BRANCH_SEARCH).performClick()
                onNodeWithTag(Tags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
                awaitNoTag(Tags.BRANCH_PICKER)
                val first = "monitored/branch-0000"
                awaitTag(ItemTags.pinnedBranch(first))

                measure("open the menu of a pinned branch", budgetMillis = 2_000, budgetMegabytes = 50) {
                    onNodeWithTag(ItemTags.pinnedBranchMenu(first)).performClick()
                    awaitTag(ItemTags.branchMenuPush(first))
                    renderFrame()
                }
                // The push itself is one Git process; what is measured is the refresh of $BRANCHES branches after it.
                measure("push a pinned branch, then refresh", budgetMillis = 10_000, budgetMegabytes = 150) {
                    onNodeWithTag(ItemTags.branchMenuPush(first)).performClick()
                    awaitCondition(timeout = 20_000) { app.project.branchSync?.running == false }
                    awaitCondition { app.project.gitBranchInfos.any { it.name == first && it.hasUpstream } }
                    renderFrame()
                }
                assertEquals(false, app.project.branchSync?.isError, app.project.branchSync?.text)
                val chips = onAllNodes(hasTestTagPrefix("branch.pinned:")).fetchSemanticsNodes().size
                assertTrue(chips < MAX_COMPOSED_ROWS, "composed toolbar chips: $chips")
            } finally {
                remote.toFile().deleteRecursively()
            }
        }

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

            measureWheel(list)
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed branch rows after the wheel: ${composedRows()}")

            measure("search one branch among $BRANCHES", budgetMillis = 5_000, budgetMegabytes = 50) {
                onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("branch-2999")
                awaitTag(ItemTags.branchItem("monitored/branch-2999"))
                renderFrame()
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed branch rows after search: ${composedRows()}")
        }
}
