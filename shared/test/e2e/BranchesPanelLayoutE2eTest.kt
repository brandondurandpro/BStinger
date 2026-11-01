package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.BranchSection
import org.example.kotlineditor.state.Pane
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A pixel of rounding either way between the dragged distance and the laid out width.
private const val TOLERANCE_DP = 2f

/** Layout of the Branches window: its resizable panes, and the folding of the local and remote sections. */
@OptIn(ExperimentalTestApi::class)
class BranchesPanelLayoutE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    // Two local branches, one of them pushed to a bare remote: both sections have rows.
    private fun repository(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        project.git("branch", "feature/login")
        return default
    }

    private fun ComposeUiTest.openBranches(app: E2eApp, default: String) {
        awaitCondition { app.project.gitBranchInfos.size == 2 }
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
        awaitTag(GitTags.historyBranch("origin/$default"))
    }

    private fun ComposeUiTest.widthOf(tag: String): Dp = onNodeWithTag(tag).getBoundsInRoot().let { it.right - it.left }

    private fun ComposeUiTest.drag(tag: String, by: Dp) {
        val steps = 10
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press()
            repeat(steps) { moveBy(Offset(by.toPx() / steps, 0f)) }
            release()
        }
        waitForIdle()
    }

    private fun assertClose(expected: Dp, actual: Dp, what: String) =
        assertTrue((expected - actual).value in -TOLERANCE_DP..TOLERANCE_DP, "$what: expected $expected, was $actual")

    private fun ComposeUiTest.selectHead() {
        val head = project.git("rev-parse", "HEAD").trim()
        awaitTag(GitTags.historyCommit(head)).performClick()
        awaitTag(GitTags.HISTORY_DETAILS)
    }

    @Test
    fun `the branch list and the commit details are resized with their splitters, and keep their width`() =
        runComposeUiTest {
            val default = repository()
            val app = launchWithProject(project)
            openBranches(app, default)
            selectHead()
            val branches = widthOf(GitTags.HISTORY_BRANCH_SEARCH)
            val details = widthOf(GitTags.HISTORY_DETAILS)

            // The branch list widens to the right; the details widen to the left.
            drag(GitTags.HISTORY_BRANCHES_SPLITTER, 60.dp)
            assertClose(branches + 60.dp, widthOf(GitTags.HISTORY_BRANCH_SEARCH), "branch list")
            drag(GitTags.HISTORY_DETAILS_SPLITTER, (-80).dp)
            assertClose(details + 80.dp, widthOf(GitTags.HISTORY_DETAILS), "details")

            // Hidden and shown again, the window keeps its panes, and the layout preferences hold them.
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitNoTag(GitTags.BRANCHES_PANEL)
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitTag(GitTags.HISTORY_DETAILS)
            assertClose(branches + 60.dp, widthOf(GitTags.HISTORY_BRANCH_SEARCH), "branch list after reopening")
            assertClose(details + 80.dp, widthOf(GitTags.HISTORY_DETAILS), "details after reopening")
            val saved = app.project.toolWindows.toPreferences()
            val savedWidth = saved.getValue("layout.pane.HistoryBranches").toFloat()
            assertEquals(Pane.HistoryBranches.defaultWidth + 60f, savedWidth, 1f)
        }

    @Test
    fun `a pane dragged too far stays within its limits and never hides the commits`() =
        runComposeUiTest {
            val default = repository()
            val app = launchWithProject(project)
            openBranches(app, default)
            selectHead()

            drag(GitTags.HISTORY_BRANCHES_SPLITTER, (-1000).dp)
            assertEquals(120f, app.project.toolWindows.widthOf(Pane.HistoryBranches))
            // Both side panes as wide as they can be: the window shrinks them, the commit list keeps its width.
            drag(GitTags.HISTORY_BRANCHES_SPLITTER, 3000.dp)
            drag(GitTags.HISTORY_DETAILS_SPLITTER, (-3000).dp)
            assertEquals(900f, app.project.toolWindows.widthOf(Pane.HistoryBranches))
            val head = project.git("rev-parse", "HEAD").trim()
            val commitRow = widthOf(GitTags.historyCommit(head))
            assertTrue(commitRow >= 199.dp, "commit list width: $commitRow")
            // Still usable: a click on another commit row shows it.
            onNodeWithTag(GitTags.historyCommit(head)).performClick()
            awaitTag(GitTags.HISTORY_DETAILS)
        }

    @Test
    fun `the local and remote sections fold and unfold, and keep their state`() =
        runComposeUiTest {
            val default = repository()
            val app = launchWithProject(project)
            openBranches(app, default)
            awaitTag(GitTags.historyBranch("feature/login"))

            onNodeWithTag(GitTags.historySection(BranchSection.Local.name)).performClick()
            awaitNoTag(GitTags.historyBranch("feature/login"))
            awaitNoTag(GitTags.historyBranch(default))
            awaitTag(GitTags.historyBranch("origin/$default"))
            // The header still counts the folded branches.
            awaitTag(GitTags.historySection(BranchSection.Local.name))
                .assertTextContains("2", substring = true)

            onNodeWithTag(GitTags.historySection(BranchSection.Remote.name)).performClick()
            awaitNoTag(GitTags.historyBranch("origin/$default"))

            // Folded sections stay folded while filtering, and after the window is hidden and shown again.
            onNodeWithTag(GitTags.HISTORY_BRANCH_SEARCH).performTextInput("login")
            awaitTag(GitTags.historySection(BranchSection.Local.name)).assertTextContains("1", substring = true)
            awaitNoTag(GitTags.historyBranch("feature/login"))
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitNoTag(GitTags.BRANCHES_PANEL)
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitTag(GitTags.historySection(BranchSection.Local.name))
            awaitNoTag(GitTags.historyBranch("feature/login"))

            onNodeWithTag(GitTags.historySection(BranchSection.Local.name)).performClick()
            awaitTag(GitTags.historyBranch("feature/login"))
            awaitNoTag(GitTags.historyBranch("origin/$default"))
            // The selection does not depend on the folding: the history still shows the current branch.
            assertEquals(default, app.project.history.selectedRef)
        }
}
