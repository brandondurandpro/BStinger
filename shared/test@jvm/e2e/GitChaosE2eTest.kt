package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Slow (a 600 ms round trip), but under the 1 s after which the chaos client treats a silent link as dead.
private const val SLOW_BRANCH_LINK_MS = 300L

/** Git, which runs on the server, while the network fails. */
@OptIn(ExperimentalTestApi::class)
class GitChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the Git panel opened while the network is down lists the changes once it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project, chaos = true)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            app.network.comeBack()
            awaitTag(ItemTags.gitChange("README.md"))
        }

    @Test
    fun `the branch picker keeps its branches while the network is down and refreshes when it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature" } }
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            // The last known branches stay usable; the refresh issued on opening waits for the link.
            awaitTag(ItemTags.branchItem("feature"))
            onNodeWithTag(Tags.BRANCH_REFRESH).performClick()
            app.network.comeBack()
            awaitReconnected()
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature" } }
            awaitTag(ItemTags.branchItem("feature"))
        }

    @Test
    fun `the branch picker survives a server restart and refreshes the branches after it`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.isNotEmpty() }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            project.git("branch", "after-restart")

            // The restarted server loses its sessions; the panel must stay usable and reach the new one.
            app.process.restart()
            awaitReconnected()
            awaitTag(Tags.BRANCH_PICKER)
            onNodeWithTag(Tags.BRANCH_REFRESH).performClick()
            awaitTag(ItemTags.branchItem("after-restart"))
        }

    @Test
    fun `a slow link still opens the picker and refreshes its branches`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.any { it.name == default } }
            project.git("branch", "slow-link")
            app.network.latencyMs = SLOW_BRANCH_LINK_MS

            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            // The panel and its cached rows open without waiting for the slow round trip it triggers.
            awaitTag(ItemTags.branchItem(default), timeout = 2_000)

            // Once the refresh crosses the link, the new branch shows up under the current query.
            onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("slow-link")
            awaitTag(ItemTags.branchItem("slow-link"), timeout = 15_000)
        }

    private fun localBranches(): Set<String> =
        project
            .git("for-each-ref", "--format=%(refname:short)", "refs/heads")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    // Opens the cleanup dialog of the branch picker on the branches `a` and `b`, which have no remote.
    private fun ComposeUiTest.openCleanup(app: E2eApp) {
        awaitCondition { app.project.gitBranchInfos.count { it.name == "a" || it.name == "b" } == 2 }
        onNodeWithTag(Tags.GIT_BRANCH).performClick()
        awaitTag(Tags.BRANCH_PICKER)
        onNodeWithTag(Tags.BRANCH_CLEANUP).performClick()
        awaitTag(ItemTags.branchToDelete("a"))
        awaitTag(ItemTags.branchToDelete("b"))
    }

    @Test
    fun `a branch deletion whose answer is lost deletes once and reports success`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("a", "b").forEach { project.git("branch", it) }
            val app = launchWithProject(project, chaos = true)
            openCleanup(app)
            // The branches are deleted, then the connection breaks before the answer: the client sends it again, and
            // the server finds them already gone, which is not an error.
            app.network.dropFromServer = true
            onNodeWithTag(Tags.BRANCH_DELETE_CONFIRM).performClick()
            awaitCondition { localBranches() == setOf(default) }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitTag(Tags.BRANCH_DONE)
            awaitNoTag(Tags.BRANCH_ERROR)
            assertEquals("2 branches deleted", app.project.gitMessage)
            assertFalse(app.project.gitMessageIsError)
            awaitNoTag(ItemTags.branchItem("a"))
        }

    @Test
    fun `a branch deletion confirmed while the network is down says so and deletes once it is back`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("a", "b").forEach { project.git("branch", it) }
            val app = launchWithProject(project, chaos = true)
            openCleanup(app)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.BRANCH_DELETE_CONFIRM).performClick()
            // Nothing pretends to be done: the dialog says it is deleting, and the disk still has the branches.
            awaitTag(Tags.BRANCH_DELETING)
            onNodeWithTag(Tags.BRANCH_DELETE_CONFIRM).assertIsNotEnabled()
            assertEquals(setOf(default, "a", "b"), localBranches())
            app.network.comeBack()
            awaitReconnected()
            awaitTag(Tags.BRANCH_DONE)
            assertEquals(setOf(default), localBranches())
        }

    @Test
    fun `the deletion dialog survives a server restart and deletes on the new server`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("a", "b").forEach { project.git("branch", it) }
            val app = launchWithProject(project, chaos = true)
            openCleanup(app)
            app.process.restart()
            awaitReconnected()
            awaitTag(Tags.BRANCH_DELETE_DIALOG)
            onNodeWithTag(Tags.BRANCH_DELETE_CONFIRM).performClick()
            awaitTag(Tags.BRANCH_DONE)
            assertEquals(setOf(default), localBranches())
        }

    // `feature` tracks a bare remote and has one local commit to push; it is pinned in the toolbar.
    private fun ComposeUiTest.pinnedFeatureAheadOfRemote(remote: Path): E2eApp {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        project.git("checkout", "-q", "-b", "feature")
        project.git("push", "-q", "-u", "origin", "feature")
        project.git("commit", "-q", "--allow-empty", "-m", "local work")
        project.git("checkout", "-q", default)
        val app = launchWithProject(project, chaos = true)
        awaitCondition { app.project.gitBranchInfos.any { it.name == "feature" && it.ahead == 1 } }
        onNodeWithTag(Tags.GIT_BRANCH).performClick()
        awaitTag(Tags.BRANCH_PICKER)
        onNodeWithTag(ItemTags.branchPin("feature")).performClick()
        onNodeWithTag(Tags.BRANCH_SEARCH).performClick()
        onNodeWithTag(Tags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
        awaitTag(ItemTags.pinnedBranch("feature"))
        return app
    }

    private fun ComposeUiTest.pushFeatureFromItsChip() {
        onNodeWithTag(ItemTags.pinnedBranchMenu("feature")).performClick()
        awaitTag(ItemTags.branchMenuPush("feature")).performClick()
    }

    @Test
    fun `a push of a pinned branch whose answer is lost is reported as done`() =
        runComposeUiTest {
            val remote = Files.createTempDirectory("kotlin-editor-remote")
            try {
                val app = pinnedFeatureAheadOfRemote(remote)
                // The push is made, then the connection breaks before its answer: the retried push changes nothing.
                app.network.dropFromServer = true
                pushFeatureFromItsChip()
                awaitCondition {
                    gitIn(remote, "rev-parse", "feature").trim() == project.git("rev-parse", "feature").trim()
                }
                app.network.cutAll()
                app.network.dropFromServer = false
                awaitCondition { app.project.branchSync?.running == false }
                assertEquals(false, app.project.branchSync?.isError, app.project.branchSync?.text)
                awaitNoTag(ItemTags.branchAhead("feature"))
            } finally {
                remote.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a push of a pinned branch while the network is down says it is pushing, then pushes`() =
        runComposeUiTest {
            val remote = Files.createTempDirectory("kotlin-editor-remote")
            try {
                val app = pinnedFeatureAheadOfRemote(remote)
                app.network.goDown()
                awaitReconnecting()
                pushFeatureFromItsChip()
                awaitTag(Tags.BRANCH_SYNC_STATUS)
                assertEquals(true, app.project.branchSync?.running)
                app.network.comeBack()
                awaitReconnected()
                awaitCondition { app.project.branchSync?.running == false }
                assertEquals(false, app.project.branchSync?.isError, app.project.branchSync?.text)
                assertEquals(project.git("rev-parse", "feature").trim(), gitIn(remote, "rev-parse", "feature").trim())
            } finally {
                remote.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a commit whose answer is lost is made once`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project, chaos = true)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            awaitTag(Tags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            // The commit is made, then the connection breaks before its answer arrives: the client sends it again.
            app.network.dropFromServer = true
            onNodeWithTag(Tags.GIT_COMMIT).performClick()
            awaitCondition { project.lastCommitSubject() == "Update the readme" }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitCondition { !app.project.gitBusy && app.project.gitStatus.isEmpty() }
            assertEquals("Committed", app.project.gitMessage)
            assertEquals(2, project.commitCount(), "the retried commit must not run twice")
        }

    @Test
    fun `Open File from a diff while the network is down shows the file once it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project, chaos = true)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md")).performClick()
            awaitTag(Tags.DIFF_OPEN_FILE)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.DIFF_OPEN_FILE).performClick()
            awaitTag(Tags.tab("README.md"))
            // No content is made up while the file cannot be read.
            val editor = app.project.editor
            assertEquals(null, editor.activeTab?.document)
            app.network.comeBack()
            awaitReconnected()
            awaitCondition {
                val document = editor.activeTab?.document
                document != null && document.lines.firstOrNull() == "# Changed"
            }
        }
}
