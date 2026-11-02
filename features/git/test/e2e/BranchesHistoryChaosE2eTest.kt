package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Slow (a 600 ms round trip), but under the 1 s after which the chaos client treats a silent link as dead.
private const val SLOW_LINK_MS = 300L

/** The Branches tool window, whose history comes from the server, while the network fails. */
@OptIn(ExperimentalTestApi::class)
class BranchesHistoryChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun hashOf(ref: String) = project.git("rev-parse", ref).trim()

    // Two branches off the default one, with a commit of their own each.
    private fun twoBranches() {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        listOf("first", "second").forEach {
            project.git("checkout", "-q", "-b", it, default)
            project.git("commit", "-q", "--allow-empty", "-m", "only on $it")
        }
        project.git("checkout", "-q", default)
    }

    private fun ComposeUiTest.openBranches(app: E2eApp) {
        awaitCondition { app.project.git.branchInfos.size == 3 }
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
        awaitTag(GitTags.historyBranch("second"))
    }

    @Test
    fun `on a slow link the history of a branch left meanwhile never replaces the one selected`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            app.network.latencyMs = SLOW_LINK_MS
            onNodeWithTag(GitTags.historyBranch("first")).performClick()
            onNodeWithTag(GitTags.historyBranch("second")).performClick()

            awaitTag(GitTags.historyCommit(hashOf("second")), timeout = 15_000)
            awaitCondition(timeout = 15_000) { !app.project.git.history.loading }
            // The answer for "first" arrived late, and was dropped.
            awaitNoTag(GitTags.historyCommit(hashOf("first")))
            assertEquals("second", app.project.git.history.selectedRef)
            val left = hashOf("first")
            val shown = app.project.git.history.commits
            assertTrue(shown.none { it.hash == left })
        }

    @Test
    fun `a history asked while the network is down shows it is loading, then loads once it is back`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(GitTags.historyBranch("first")).performClick()
            awaitTag(GitTags.HISTORY_LOADING)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(GitTags.historyCommit(hashOf("first")))
            awaitNoTag(GitTags.HISTORY_LOADING)
        }

    @Test
    fun `the window survives a server restart and its refresh reaches the new server`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            project.git("commit", "-q", "--allow-empty", "-m", "after the restart")
            app.process.restart()
            awaitReconnected()
            awaitTag(GitTags.BRANCHES_PANEL)
            onNodeWithTag(GitTags.HISTORY_REFRESH).performClick()
            awaitTag(GitTags.historyCommit(hashOf("HEAD")))
        }

    @Test
    fun `on a slow link the results of an older search never replace the newer one`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            onNodeWithTag(GitTags.historyBranch("first")).performClick()
            awaitTag(GitTags.historyCommit(hashOf("first")))
            app.network.latencyMs = SLOW_LINK_MS

            onNodeWithTag(GitTags.HISTORY_SEARCH).performTextInput("only")
            awaitCondition { app.project.git.history.appliedQuery == "only" }
            // A second search before the first one answered: its results are the only ones shown.
            onNodeWithTag(GitTags.HISTORY_SEARCH).performTextInput(" on first")
            awaitCondition(timeout = 15_000) {
                app.project.git.history.appliedQuery == "only on first" && app.project.git.history.complete
            }
            val history = app.project.git.history
            val subjects = history.commits.map { it.subject }
            assertEquals(listOf("only on first"), subjects)
            awaitNoTag(GitTags.historyCommit(hashOf("HEAD")))
        }

    @Test
    fun `a search typed while the network is down runs once it is back`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            onNodeWithTag(GitTags.historyBranch("second")).performClick()
            awaitTag(GitTags.historyCommit(hashOf("second")))
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(GitTags.HISTORY_SEARCH).performTextInput("init")
            awaitTag(GitTags.HISTORY_LOADING)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(GitTags.historyCommit(hashOf("second~1")))
            awaitNoTag(GitTags.historyCommit(hashOf("second")))
            awaitNoTag(GitTags.HISTORY_LOADING)
        }

    @Test
    fun `a commit diff opened while the network is down loads once it is back`() =
        runComposeUiTest {
            twoBranches()
            project.file("README.md", textOf("# Changed on second", ""))
            project.git("checkout", "-q", "second")
            project.git("commit", "-q", "-am", "edit the readme")
            val edit = hashOf("HEAD")
            project.git("checkout", "-q", "-")
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            onNodeWithTag(GitTags.historyBranch("second")).performClick()
            awaitTag(GitTags.historyCommit(edit)).performClick()
            awaitTag(GitTags.historyFile("README.md"))

            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(GitTags.historyFile("README.md")).performClick()
            awaitTag(EditorTags.commitDiffTab(edit, "README.md"))
            // Nothing pretends to be loaded while the link is down.
            val editor = app.project.editor
            assertTrue(editor.activeTab?.status !is TabStatus.Diff)
            app.network.comeBack()
            awaitReconnected()
            awaitCondition {
                val diff = editor.activeTab?.status as? TabStatus.Diff
                diff != null && diff.lines.isNotEmpty()
            }
            awaitTag(EditorTags.DIFF)
        }
}
