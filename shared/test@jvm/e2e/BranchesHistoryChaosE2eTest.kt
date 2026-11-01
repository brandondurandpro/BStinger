package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
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
        awaitCondition { app.project.gitBranchInfos.size == 3 }
        onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
        awaitTag(Tags.BRANCHES_PANEL)
        awaitTag(ItemTags.historyBranch("second"))
    }

    @Test
    fun `on a slow link the history of a branch left meanwhile never replaces the one selected`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            app.network.latencyMs = SLOW_LINK_MS
            onNodeWithTag(ItemTags.historyBranch("first")).performClick()
            onNodeWithTag(ItemTags.historyBranch("second")).performClick()

            awaitTag(ItemTags.historyCommit(hashOf("second")), timeout = 15_000)
            awaitCondition(timeout = 15_000) { !app.project.history.loading }
            // The answer for "first" arrived late, and was dropped.
            awaitNoTag(ItemTags.historyCommit(hashOf("first")))
            assertEquals("second", app.project.history.selectedRef)
            val left = hashOf("first")
            val shown = app.project.history.commits
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
            onNodeWithTag(ItemTags.historyBranch("first")).performClick()
            awaitTag(Tags.HISTORY_LOADING)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(ItemTags.historyCommit(hashOf("first")))
            awaitNoTag(Tags.HISTORY_LOADING)
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
            awaitTag(Tags.BRANCHES_PANEL)
            onNodeWithTag(Tags.HISTORY_REFRESH).performClick()
            awaitTag(ItemTags.historyCommit(hashOf("HEAD")))
        }

    @Test
    fun `on a slow link the results of an older search never replace the newer one`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            onNodeWithTag(ItemTags.historyBranch("first")).performClick()
            awaitTag(ItemTags.historyCommit(hashOf("first")))
            app.network.latencyMs = SLOW_LINK_MS

            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("only")
            awaitCondition { app.project.history.appliedQuery == "only" }
            // A second search before the first one answered: its results are the only ones shown.
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput(" on first")
            awaitCondition(timeout = 15_000) {
                app.project.history.appliedQuery == "only on first" && app.project.history.complete
            }
            val history = app.project.history
            val subjects = history.commits.map { it.subject }
            assertEquals(listOf("only on first"), subjects)
            awaitNoTag(ItemTags.historyCommit(hashOf("HEAD")))
        }

    @Test
    fun `a search typed while the network is down runs once it is back`() =
        runComposeUiTest {
            twoBranches()
            val app = launchWithProject(project, chaos = true)
            openBranches(app)
            onNodeWithTag(ItemTags.historyBranch("second")).performClick()
            awaitTag(ItemTags.historyCommit(hashOf("second")))
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("init")
            awaitTag(Tags.HISTORY_LOADING)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(ItemTags.historyCommit(hashOf("second~1")))
            awaitNoTag(ItemTags.historyCommit(hashOf("second")))
            awaitNoTag(Tags.HISTORY_LOADING)
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
            onNodeWithTag(ItemTags.historyBranch("second")).performClick()
            awaitTag(ItemTags.historyCommit(edit)).performClick()
            awaitTag(ItemTags.historyFile("README.md"))

            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(ItemTags.historyFile("README.md")).performClick()
            awaitTag(Tags.commitDiffTab(edit, "README.md"))
            // Nothing pretends to be loaded while the link is down.
            val editor = app.project.editor
            assertTrue(editor.activeTab?.status !is TabStatus.Diff)
            app.network.comeBack()
            awaitReconnected()
            awaitCondition {
                val diff = editor.activeTab?.status as? TabStatus.Diff
                diff != null && diff.lines.isNotEmpty()
            }
            awaitTag(Tags.DIFF)
        }
}
