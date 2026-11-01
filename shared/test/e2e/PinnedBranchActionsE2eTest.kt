package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The menu of a branch pinned in the toolbar: pull and push it without checking it out, show the changes. */
@OptIn(ExperimentalTestApi::class)
class PinnedBranchActionsE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extras.add(it) }

    // The project tracks a bare remote; `feature` is pushed and tracked. Returns the default branch and the remote.
    private fun projectWithRemote(): Pair<String, Path> {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = temporaryDirectory("kotlin-editor-remote")
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        project.git("branch", "feature")
        project.git("push", "-q", "-u", "origin", "feature")
        return default to remote
    }

    // Adds a commit to `feature` on the remote, through a clone, and fetches it.
    private fun advanceRemoteFeature(remote: Path, file: String = "remote.txt") {
        val clone = temporaryDirectory("kotlin-editor-clone")
        gitIn(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
        gitIn(clone, "checkout", "-q", "feature")
        clone.resolve(file).writeText("from the remote\n")
        gitIn(clone, "add", "-A")
        gitIn(clone, "commit", "-q", "-m", "remote work")
        gitIn(clone, "push", "-q")
        project.git("fetch", "-q")
    }

    private fun commitOnFeature(default: String, message: String) {
        project.git("checkout", "-q", "feature")
        project.git("commit", "-q", "--allow-empty", "-m", message)
        project.git("checkout", "-q", default)
    }

    private fun ComposeUiTest.pin(app: E2eApp, branch: String) {
        awaitCondition { app.project.gitBranchInfos.any { it.name == branch } }
        onNodeWithTag(Tags.GIT_BRANCH).performClick()
        awaitTag(Tags.BRANCH_PICKER)
        onNodeWithTag(ItemTags.branchPin(branch)).performClick()
        awaitCondition { branch in app.project.selectedBranches }
        onNodeWithTag(Tags.BRANCH_SEARCH).performClick()
        onNodeWithTag(Tags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
        awaitNoTag(Tags.BRANCH_PICKER)
        awaitTag(ItemTags.pinnedBranch(branch))
    }

    private fun ComposeUiTest.menuItem(branch: String, tag: String) {
        onNodeWithTag(ItemTags.pinnedBranchMenu(branch)).performClick()
        awaitTag(tag)
    }

    private fun ComposeUiTest.awaitSyncStatus(text: String) {
        awaitCondition {
            runCatching { onNodeWithTag(Tags.BRANCH_SYNC_STATUS).assertTextContains(text, substring = true) }.isSuccess
        }
    }

    @Test
    fun `a pinned branch ahead of its remote is pushed without checking it out`() =
        runComposeUiTest {
            val (default, remote) = projectWithRemote()
            commitOnFeature(default, "local work")
            val app = launchWithProject(project)
            pin(app, "feature")
            awaitTag(ItemTags.branchAhead("feature"), unmerged = true)

            menuItem("feature", ItemTags.branchMenuPush("feature"))
            onNodeWithTag(ItemTags.branchMenuPush("feature")).assertTextContains("Push ↑ 1", substring = true)
            onNodeWithTag(ItemTags.branchMenuPush("feature")).performClick()

            awaitSyncStatus("Pushed feature")
            assertEquals(project.git("rev-parse", "feature").trim(), gitIn(remote, "rev-parse", "feature").trim())
            // The chip no longer shows a commit to push, and the current branch did not change.
            awaitNoTag(ItemTags.branchAhead("feature"))
            assertEquals(default, app.project.gitBranch)
            onNodeWithTag(Tags.BRANCH_SYNC_DISMISS).performClick()
            awaitNoTag(Tags.BRANCH_SYNC_STATUS)
        }

    @Test
    fun `a pinned branch behind its remote is pulled without touching the working tree`() =
        runComposeUiTest {
            val (default, remote) = projectWithRemote()
            advanceRemoteFeature(remote)
            val app = launchWithProject(project)
            pin(app, "feature")
            awaitTag(ItemTags.branchBehind("feature"), unmerged = true)

            menuItem("feature", ItemTags.branchMenuPull("feature"))
            onNodeWithTag(ItemTags.branchMenuPull("feature")).assertTextContains("Pull ↓ 1", substring = true)
            onNodeWithTag(ItemTags.branchMenuPull("feature")).performClick()

            awaitSyncStatus("Pulled feature")
            assertEquals(gitIn(remote, "rev-parse", "feature").trim(), project.git("rev-parse", "feature").trim())
            awaitNoTag(ItemTags.branchBehind("feature"))
            assertEquals(default, app.project.gitBranch)
            assertFalse(project.root.resolve("remote.txt").exists(), "the checked-out files must not change")
        }

    @Test
    fun `a diverged branch is not pulled, and the toolbar says why`() =
        runComposeUiTest {
            val (default, remote) = projectWithRemote()
            advanceRemoteFeature(remote)
            commitOnFeature(default, "local work")
            val before = project.git("rev-parse", "feature").trim()
            val app = launchWithProject(project)
            pin(app, "feature")

            menuItem("feature", ItemTags.branchMenuPull("feature"))
            onNodeWithTag(ItemTags.branchMenuPull("feature")).performClick()
            awaitSyncStatus("Cannot pull feature")
            assertTrue(app.project.branchSync?.isError == true)
            assertEquals(before, project.git("rev-parse", "feature").trim())
            // Dismissing the error clears it; nothing stays pretending to work.
            onNodeWithTag(Tags.BRANCH_SYNC_DISMISS).performClick()
            awaitNoTag(Tags.BRANCH_SYNC_STATUS)
            assertFalse(app.project.gitBusy)
        }

    @Test
    fun `a branch without a remote cannot be pulled, and pushing it creates its remote branch`() =
        runComposeUiTest {
            projectWithRemote()
            project.git("branch", "fresh")
            val app = launchWithProject(project)
            pin(app, "fresh")

            menuItem("fresh", ItemTags.branchMenuPull("fresh"))
            onNodeWithTag(ItemTags.branchMenuPull("fresh")).assertIsNotEnabled()
            onNodeWithTag(ItemTags.branchMenuPush("fresh")).assertTextContains("Push to origin/fresh", substring = true)
            onNodeWithTag(ItemTags.branchMenuPush("fresh")).performClick()

            awaitSyncStatus("Pushed fresh")
            assertEquals("origin/fresh", project.git("rev-parse", "--abbrev-ref", "fresh@{upstream}").trim())
            awaitCondition { app.project.gitBranchInfos.any { it.name == "fresh" && it.hasUpstream } }
        }

    @Test
    fun `Show Changes opens the Git tool window, and Checkout and Unpin work from the menu`() =
        runComposeUiTest {
            projectWithRemote()
            val app = launchWithProject(project)
            pin(app, "feature")
            if (app.project.toolWindows.isVisible(ToolWindow.Git)) onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitNoTag(Tags.GIT_PANEL)

            menuItem("feature", ItemTags.branchMenuChanges("feature"))
            onNodeWithTag(ItemTags.branchMenuChanges("feature")).performClick()
            awaitTag(Tags.GIT_PANEL)

            menuItem("feature", ItemTags.branchMenuUnpin("feature"))
            onNodeWithTag(ItemTags.branchMenuUnpin("feature")).performClick()
            awaitNoTag(ItemTags.pinnedBranch("feature"))

            pin(app, "feature")
            menuItem("feature", ItemTags.branchMenuCheckout("feature"))
            onNodeWithTag(ItemTags.branchMenuCheckout("feature")).performClick()
            awaitCondition { app.project.gitBranch == "feature" }
        }
}
