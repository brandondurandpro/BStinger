package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.BranchSection
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val COMMITS = 5_000
private const val BRANCHES = 1_000
private const val SCROLL_STEPS = 50
private const val MAX_COMPOSED_ROWS = 80
private const val DRAG_STEPS = 30

/**
 * Performance of the Branches window on a repository of [COMMITS] commits and [BRANCHES] branches: opening it,
 * scrolling the whole history (loaded page by page), and selecting a commit. Both lists are virtualized.
 */
@OptIn(ExperimentalTestApi::class)
class BranchesHistoryPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // `git fast-import` writes thousands of commits in one process; a commit each through `git commit` takes minutes.
    private fun createHistory(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val script = StringBuilder()
        repeat(COMMITS) { index ->
            val message = "commit $index of the long history"
            script.append("commit refs/heads/$default\n")
            script.append("committer Test <test@example.com> ${1_700_000_000 + index} +0000\n")
            script.append("data ${message.toByteArray().size}\n$message\n")
            if (index == 0) script.append("from $default^0\n")
            val content = "$index\n"
            script.append("M 644 inline history.txt\n")
            script.append("data ${content.length}\n$content\n")
            if (index % (COMMITS / BRANCHES) == 0) {
                script.append("reset refs/heads/history/branch-${index.toString().padStart(4, '0')}\n")
                script.append("from refs/heads/$default\n\n")
            }
        }
        val process =
            ProcessBuilder("git", "fast-import", "--quiet", "--force")
                .directory(project.root.toFile())
                .redirectErrorStream(true)
                .start()
        process.outputStream.use { it.write(script.toString().toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git fast-import failed: $output" }
        project.git("reset", "-q", "--hard", default)
        return default
    }

    private fun ComposeUiTest.composedCommitRows() =
        onAllNodes(hasTestTagPrefix("history.commit:")).fetchSemanticsNodes().size

    @Test
    fun `a history of thousands of commits`() =
        runComposeUiTest {
            createHistory()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.size > BRANCHES }
            val newest = project.git("rev-parse", "HEAD").trim()

            measure("open the Branches window on $COMMITS commits", budgetMillis = 5_000, budgetMegabytes = 100) {
                onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
                awaitTag(ItemTags.historyCommit(newest))
                renderFrame()
            }
            assertTrue(composedCommitRows() < MAX_COMPOSED_ROWS, "composed commit rows: ${composedCommitRows()}")

            val list = onNode(hasScrollToIndexInHistory())
            measure("scroll the whole history, page by page", budgetMillis = 30_000, budgetMegabytes = 600) {
                repeat(SCROLL_STEPS) { step ->
                    awaitCondition { !app.project.history.loading }
                    val target = (step + 1) * (COMMITS / SCROLL_STEPS)
                    list.performScrollToIndex(minOf(target, app.project.history.commits.size - 1))
                    renderFrame()
                }
                awaitCondition(timeout = 20_000) { app.project.history.complete }
            }
            assertTrue(app.project.history.commits.size > COMMITS, "loaded: ${app.project.history.commits.size}")
            assertTrue(composedCommitRows() < MAX_COMPOSED_ROWS, "composed after scrolling: ${composedCommitRows()}")

            val oldest = app.project.history.commits[COMMITS / 2]
            measure("select a commit and show its files", budgetMillis = 3_000, budgetMegabytes = 50) {
                list.performScrollToIndex(COMMITS / 2)
                onNodeWithTag(ItemTags.historyCommit(oldest.hash)).performClick()
                awaitTag(ItemTags.historyFile("history.txt"))
                renderFrame()
            }
            val branchRows = onAllNodes(hasTestTagPrefix("history.branch:")).fetchSemanticsNodes().size
            assertTrue(branchRows < MAX_COMPOSED_ROWS, "composed branch rows: $branchRows")

            val local = ItemTags.historySection(BranchSection.Local.name)
            measure("fold the $BRANCHES local branches", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(local).performClick()
                awaitNoTag(ItemTags.historyBranch("history/branch-0000"))
                renderFrame()
            }
            measure("unfold the $BRANCHES local branches", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(local).performClick()
                awaitTag(ItemTags.historyBranch("history/branch-0000"))
                renderFrame()
            }
            val unfolded = onAllNodes(hasTestTagPrefix("history.branch:")).fetchSemanticsNodes().size
            assertTrue(unfolded < MAX_COMPOSED_ROWS, "composed branch rows after unfolding: $unfolded")
            measure("widen the branch list by dragging its splitter", budgetMillis = 2_000, budgetMegabytes = 50) {
                onNodeWithTag(Tags.HISTORY_BRANCHES_SPLITTER).performMouseInput {
                    moveTo(center)
                    press()
                    repeat(DRAG_STEPS) { moveBy(Offset(4f, 0f)) }
                    release()
                }
                renderFrame()
            }
        }
}
