package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COMMITS = 3_000
private const val BRANCHES = 1_000
private const val MAX_COMPOSED_ROWS = 80

/**
 * The menus of the Branches window on a repository of [COMMITS] commits and [BRANCHES] branches: opening them, and
 * the actions of a commit deep in the history, must not depend on the size of the lists.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryActionsPerformanceE2eTest {
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
        gitFastImport(project.root, script.toString())
        project.git("reset", "-q", "--hard", default)
        return default
    }

    private fun rightClick(node: SemanticsNodeInteraction) = node.performMouseInput { rightClick() }

    private fun ComposeUiTest.composedRows(prefix: String) =
        onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes().size

    @Test
    fun `the menus open at once and act on a commit of a long history`() =
        runComposeUiTest {
            val default = createHistory()
            val app = launchWithProject(project)
            awaitCondition { app.project.git.branchInfos.size > BRANCHES }
            val newest = project.git("rev-parse", "HEAD").trim()
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitTag(GitTags.historyCommit(newest))
            val deep = project.git("rev-parse", "HEAD~3").trim()
            awaitCondition {
                app.project.git.history.commits
                    .any { it.hash == deep }
            }
            awaitTag(GitTags.historyCommit(deep))

            measure("open the menu of a branch among $BRANCHES", budgetMillis = 2_000, budgetMegabytes = 50) {
                rightClick(awaitTag(GitTags.historyBranch("history/branch-0000")))
                awaitTag(GitTags.BRANCH_CONTEXT_MENU)
                renderFrame()
            }
            awaitTag(GitTags.historyMenuItem("copyName")).performClick()
            awaitNoTag(GitTags.BRANCH_CONTEXT_MENU)

            measure("open the menu of a commit among $COMMITS", budgetMillis = 2_000, budgetMegabytes = 50) {
                rightClick(awaitTag(GitTags.historyCommit(deep)))
                awaitTag(GitTags.COMMIT_CONTEXT_MENU)
                renderFrame()
            }
            assertTrue(composedRows("history.commit:") < MAX_COMPOSED_ROWS, "composed commit rows")
            awaitTag(GitTags.historyMenuItem("newTag")).performClick()

            measure("tag a commit of a $COMMITS commits history", budgetMillis = 5_000, budgetMegabytes = 100) {
                awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("deep")
                awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
                awaitNoTag(GitTags.HISTORY_DIALOG)
                renderFrame()
            }
            assertEquals(deep, project.git("rev-parse", "deep^{commit}").trim())

            measure("reset hard to a commit and reload the history", budgetMillis = 8_000, budgetMegabytes = 150) {
                rightClick(awaitTag(GitTags.historyCommit(deep)))
                awaitTag(GitTags.historyMenuItem("resetHard")).performClick()
                awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
                awaitCondition(timeout = 20_000) {
                    app.project.git.history.commits
                        .firstOrNull()
                        ?.hash == deep
                }
                renderFrame()
            }
            assertEquals(deep, project.git("rev-parse", "HEAD").trim())
            assertEquals(default, project.git("rev-parse", "--abbrev-ref", "HEAD").trim())
        }
}
