package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.state.BranchSection
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COMMITS = 5_000
private const val BRANCHES = 1_000
private const val SCROLL_STEPS = 50
private const val MAX_COMPOSED_ROWS = 80
private const val DRAG_STEPS = 30
private const val WHEEL_STEPS = 40
private const val WHEEL_DELTA = 100f

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

    private fun ComposeUiTest.measureSearch(app: E2eApp) {
        // The oldest commit only: the search reads the whole history before it can answer.
        measure("search the oldest of $COMMITS commits", budgetMillis = 5_000, budgetMegabytes = 50) {
            onNodeWithTag(GitTags.HISTORY_SEARCH).performTextInput("commit 0 of the long history")
            awaitCondition(timeout = 20_000) {
                val history = app.project.git.history
                history.appliedQuery.isNotEmpty() && history.complete
            }
            renderFrame()
        }
        assertEquals(1, app.project.git.history.commits.size)
        measure("search the $COMMITS commits, every one matching", budgetMillis = 5_000, budgetMegabytes = 100) {
            onNodeWithTag(GitTags.HISTORY_SEARCH_CLEAR).performClick()
            onNodeWithTag(GitTags.HISTORY_SEARCH).performTextInput("long history")
            awaitCondition(timeout = 20_000) {
                app.project.git.history.appliedQuery == "long history" && !app.project.git.history.loading
            }
            renderFrame()
        }
        assertTrue(composedCommitRows() < MAX_COMPOSED_ROWS, "composed rows after search: ${composedCommitRows()}")
        onNodeWithTag(GitTags.HISTORY_SEARCH_CLEAR).performClick()
    }

    private fun ComposeUiTest.visibleBranchRows(): List<String> =
        onAllNodes(hasTestTagPrefix("history.branch:"))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }

    // A wheel scroll keeps the list moving frame after frame, unlike jumping to an index. Measured net of the same
    // input plumbing and frames without movement, like the branch picker's benchmark.
    private fun ComposeUiTest.measureBranchWheel() {
        val list = onNodeWithTag(GitTags.HISTORY_BRANCH_LIST)
        list.performScrollToIndex(BRANCHES / 2)
        renderFrame()
        val before = visibleBranchRows()
        repeat(5) {
            list.performMouseInput {
                moveTo(center)
                scroll(WHEEL_DELTA)
            }
            renderFrame()
        }
        assertTrue(visibleBranchRows() != before, "the wheel must move the branch list")
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
            "scroll the $BRANCHES branches with the wheel ($WHEEL_STEPS frames)",
            wheel - idle,
            budgetMillis = 6_000,
            budgetMegabytes = 150,
        )
    }

    private fun ComposeUiTest.measureBranchList() {
        val local = GitTags.historySection(BranchSection.Local.name)
        measure("fold the $BRANCHES local branches", budgetMillis = 2_000, budgetMegabytes = 50) {
            onNodeWithTag(local).performClick()
            awaitNoTag(GitTags.historyBranch("history/branch-0000"))
            renderFrame()
        }
        measure("unfold the $BRANCHES local branches", budgetMillis = 2_000, budgetMegabytes = 50) {
            onNodeWithTag(local).performClick()
            awaitTag(GitTags.historyBranch("history/branch-0000"))
            renderFrame()
        }
        val unfolded = onAllNodes(hasTestTagPrefix("history.branch:")).fetchSemanticsNodes().size
        assertTrue(unfolded < MAX_COMPOSED_ROWS, "composed branch rows after unfolding: $unfolded")
        measureBranchWheel()
        measure("widen the branch list by dragging its splitter", budgetMillis = 2_000, budgetMegabytes = 50) {
            onNodeWithTag(GitTags.HISTORY_BRANCHES_SPLITTER).performMouseInput {
                moveTo(center)
                press()
                repeat(DRAG_STEPS) { moveBy(Offset(4f, 0f)) }
                release()
            }
            renderFrame()
        }
    }

    @Test
    fun `a history of thousands of commits`() =
        runComposeUiTest {
            createHistory()
            val app = launchWithProject(project)
            awaitCondition { app.project.git.branchInfos.size > BRANCHES }
            val newest = project.git("rev-parse", "HEAD").trim()

            measure("open the Branches window on $COMMITS commits", budgetMillis = 5_000, budgetMegabytes = 100) {
                onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
                awaitTag(GitTags.historyCommit(newest))
                renderFrame()
            }
            assertTrue(composedCommitRows() < MAX_COMPOSED_ROWS, "composed commit rows: ${composedCommitRows()}")

            val list = onNode(hasScrollToIndexInHistory())
            measure("scroll the whole history, page by page", budgetMillis = 30_000, budgetMegabytes = 600) {
                repeat(SCROLL_STEPS) { step ->
                    awaitCondition { !app.project.git.history.loading }
                    val target = (step + 1) * (COMMITS / SCROLL_STEPS)
                    list.performScrollToIndex(minOf(target, app.project.git.history.commits.size - 1))
                    renderFrame()
                }
                awaitCondition(timeout = 20_000) { app.project.git.history.complete }
            }
            assertTrue(
                app.project.git.history.commits.size > COMMITS,
                "loaded: ${app.project.git.history.commits.size}",
            )
            assertTrue(composedCommitRows() < MAX_COMPOSED_ROWS, "composed after scrolling: ${composedCommitRows()}")

            val oldest = app.project.git.history.commits[COMMITS / 2]
            measure("select a commit and show its files", budgetMillis = 3_000, budgetMegabytes = 50) {
                list.performScrollToIndex(COMMITS / 2)
                onNodeWithTag(GitTags.historyCommit(oldest.hash)).performClick()
                awaitTag(GitTags.historyFile("history.txt"))
                renderFrame()
            }
            val branchRows = onAllNodes(hasTestTagPrefix("history.branch:")).fetchSemanticsNodes().size
            assertTrue(branchRows < MAX_COMPOSED_ROWS, "composed branch rows: $branchRows")

            measureSearch(app)
            measureBranchList()
        }

    // A file of [count] lines; with [changed], every tenth line differs.
    private fun bigFile(count: Int, changed: Boolean): String =
        (0 until count).joinToString("\n", postfix = "\n") { index ->
            if (changed && index % 10 == 0) "changed $index" else "line $index"
        }

    @Test
    fun `the diff of a commit that rewrote a file of thousands of lines`() =
        runComposeUiTest {
            project.commitAll()
            val lines = 20_000
            project.file("big.txt", bigFile(lines, changed = false))
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "big file")
            // Every tenth line changes: thousands of hunks.
            project.file("big.txt", bigFile(lines, changed = true))
            project.git("commit", "-q", "-am", "rewrite")
            val head = project.git("rev-parse", "HEAD").trim()
            val app = launchWithProject(project)
            awaitCondition { app.project.git.currentBranch != null }
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitTag(GitTags.historyCommit(head)).performClick()
            awaitTag(GitTags.historyFile("big.txt"))

            val editor = app.project.editor
            measure("open the diff of a commit that changed 2000 lines", budgetMillis = 8_000, budgetMegabytes = 300) {
                onNodeWithTag(GitTags.historyFile("big.txt")).performClick()
                awaitCondition(timeout = 20_000) { editor.activeTab?.status is TabStatus.Diff }
                awaitTag(EditorTags.DIFF)
                renderFrame()
            }
            val diff = (editor.activeTab!!.status as TabStatus.Diff).lines
            assertEquals(lines / 10, diff.count { it.kind == DiffKind.Added })

            measure("open the file of $lines lines from its diff", budgetMillis = 5_000, budgetMegabytes = 200) {
                onNodeWithTag(EditorTags.DIFF_OPEN_FILE).performClick()
                awaitCondition(timeout = 20_000) { editor.activeTab?.document != null }
                renderFrame()
            }
            val document = editor.activeTab!!.document!!
            assertEquals(lines, document.lines.size - 1)
        }
}
