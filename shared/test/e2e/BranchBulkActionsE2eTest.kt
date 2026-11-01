package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bulk actions of the branch picker: tick several branches to pin, unpin or delete them, and the cleanup shortcut. */
@OptIn(ExperimentalTestApi::class)
class BranchBulkActionsE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extras.add(it) }

    private fun localBranches(): Set<String> =
        project
            .git("for-each-ref", "--format=%(refname:short)", "refs/heads")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    private fun ComposeUiTest.openPicker(app: E2eApp, vararg branches: String) {
        val names = branches.toList()
        awaitCondition { app.project.gitBranchInfos.count { it.name in names } == names.size }
        onNodeWithTag(GitTags.GIT_BRANCH).performClick()
        awaitTag(GitTags.BRANCH_PICKER)
        branches.forEach { awaitTag(GitTags.branchItem(it)) }
    }

    private fun ComposeUiTest.assertShown(text: String) =
        assertTrue(onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(), "not shown: $text")

    // The current branch tracks origin; `feature/tracked` too; `feature/old` tracked a branch deleted on the remote,
    // and `local-only` never had one.
    private fun repositoryWithRemote(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = temporaryDirectory("kotlin-editor-remote")
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        listOf("feature/tracked", "feature/old").forEach {
            project.git("branch", it)
            project.git("push", "-q", "-u", "origin", it)
        }
        project.git("push", "-q", "origin", "--delete", "feature/old")
        project.git("fetch", "-q", "--prune")
        project.git("branch", "local-only")
        return default
    }

    @Test
    fun `ticked branches are pinned and unpinned together`() =
        runComposeUiTest {
            project.commitAll()
            listOf("feature/login", "feature/search-ui", "release").forEach { project.git("branch", it) }
            val app = launchWithProject(project)
            openPicker(app, "feature/login", "feature/search-ui", "release")

            // Select all ticks only what the query shows.
            onNodeWithTag(GitTags.BRANCH_SEARCH).performTextInput("feature")
            awaitNoTag(GitTags.branchItem("release"))
            onNodeWithTag(GitTags.BRANCH_SELECT_ALL).performClick()
            awaitCondition { app.project.pickedBranches == setOf("feature/login", "feature/search-ui") }
            onNodeWithTag(GitTags.BRANCH_PICK_COUNT, useUnmergedTree = true).assertTextContains("2 selected")
            onNodeWithTag(GitTags.BRANCH_UNPIN_PICKED).assertIsNotEnabled()

            onNodeWithTag(GitTags.BRANCH_PIN_PICKED).performClick()
            awaitCondition { app.project.selectedBranches == setOf("feature/login", "feature/search-ui") }
            // The action is done: the ticks are cleared, the pins stay.
            awaitNoTag(GitTags.BRANCH_PICK_COUNT)
            assertTrue(app.project.pickedBranches.isEmpty())

            onNodeWithTag(GitTags.branchSelect("feature/login")).performClick()
            awaitTag(GitTags.BRANCH_PICK_COUNT)
            onNodeWithTag(GitTags.BRANCH_PIN_PICKED).assertIsNotEnabled()
            onNodeWithTag(GitTags.BRANCH_UNPIN_PICKED).performClick()
            awaitCondition { app.project.selectedBranches == setOf("feature/search-ui") }

            // Clear unticks without acting.
            onNodeWithTag(GitTags.branchSelect("feature/search-ui")).performClick()
            awaitTag(GitTags.BRANCH_PICK_COUNT)
            onNodeWithTag(GitTags.BRANCH_CLEAR_PICKS).performClick()
            awaitNoTag(GitTags.BRANCH_PICK_COUNT)
            assertEquals(setOf("feature/search-ui"), app.project.selectedBranches)
            onNodeWithTag(GitTags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitTag(GitTags.pinnedBranch("feature/search-ui"))
        }

    @Test
    fun `ticked branches are deleted after a confirmation that lists them`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("feature/login", "feature/search-ui", "keep").forEach { project.git("branch", it) }
            val app = launchWithProject(project)
            openPicker(app, default, "feature/login", "feature/search-ui")
            onNodeWithTag(GitTags.branchPin("feature/login")).performClick()
            listOf(default, "feature/login", "feature/search-ui").forEach {
                onNodeWithTag(GitTags.branchSelect(it)).performClick()
            }
            awaitCondition { app.project.pickedBranches.size == 3 }

            onNodeWithTag(GitTags.BRANCH_DELETE_PICKED).performClick()
            awaitTag(GitTags.BRANCH_DELETE_DIALOG)
            // Before anything is deleted, the dialog shows what will be: the current branch cannot be, and says so.
            awaitTag(GitTags.branchToDelete("feature/login"))
            awaitTag(GitTags.branchToDelete("feature/search-ui"))
            awaitNoTag(GitTags.branchToDelete(default))
            awaitNoTag(GitTags.branchToDelete("keep"))
            assertShown("$default is the current branch: it is kept.")
            onNodeWithTag(GitTags.BRANCH_DELETE_SUMMARY, useUnmergedTree = true)
                .assertTextContains("2 of 2 branches will be deleted")
            assertEquals(localBranches(), setOf(default, "feature/login", "feature/search-ui", "keep"))

            // One branch can be spared from the dialog itself.
            onNodeWithTag(GitTags.branchToDeleteCheck("feature/search-ui")).performClick()
            awaitCondition {
                runCatching {
                    onNodeWithTag(GitTags.BRANCH_DELETE_SUMMARY, useUnmergedTree = true)
                        .assertTextContains("1 of 2 branches will be deleted")
                }.isSuccess
            }
            onNodeWithTag(GitTags.BRANCH_DELETE_CONFIRM).performClick()

            awaitNoTag(GitTags.BRANCH_DELETE_DIALOG)
            awaitTag(GitTags.BRANCH_DONE)
            onNodeWithTag(GitTags.BRANCH_DONE).assertTextContains("1 branch deleted")
            assertEquals(setOf(default, "feature/search-ui", "keep"), localBranches())
            awaitNoTag(GitTags.branchItem("feature/login"))
            awaitTag(GitTags.branchItem("feature/search-ui"))
            // The deleted branch leaves the toolbar too, and the ticks are gone with it.
            awaitCondition { "feature/login" !in app.project.selectedBranches }
            awaitCondition { "feature/login" !in app.project.pickedBranches }
        }

    @Test
    fun `the cleanup shortcut deletes only the branches without a remote, after showing them`() =
        runComposeUiTest {
            val default = repositoryWithRemote()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature/old" && it.upstreamGone } }
            openPicker(app, "feature/tracked", "feature/old", "local-only")
            onNodeWithTag(GitTags.BRANCH_CLEANUP).assertIsEnabled()
            onNodeWithTag(GitTags.BRANCH_CLEANUP).assertTextContains("Clean up 2 without remote…")

            onNodeWithTag(GitTags.BRANCH_CLEANUP).performClick()
            awaitTag(GitTags.BRANCH_DELETE_DIALOG)
            awaitTag(GitTags.branchToDelete("feature/old"))
            awaitTag(GitTags.branchToDelete("local-only"))
            awaitNoTag(GitTags.branchToDelete("feature/tracked"))
            awaitNoTag(GitTags.branchToDelete(default))
            // Each row says why it is listed.
            assertShown("origin/feature/old no longer exists on the remote")
            assertShown("no remote tracking branch")

            onNodeWithTag(GitTags.BRANCH_DELETE_CONFIRM).performClick()
            awaitTag(GitTags.BRANCH_DONE)
            assertEquals(setOf(default, "feature/tracked"), localBranches())
            // Nothing is left to clean up; the remote kept its branches.
            awaitCondition { app.project.branchesWithoutRemote().isEmpty() }
            onNodeWithTag(GitTags.BRANCH_CLEANUP).assertIsNotEnabled()
            onNodeWithTag(GitTags.BRANCH_CLEANUP).assertTextContains("No branch without remote")
            assertTrue("feature/tracked" in project.git("ls-remote", "--heads", "origin"))
        }

    @Test
    fun `cancelling a deletion deletes nothing, with the button or Escape`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("a", "b").forEach { project.git("branch", it) }
            val app = launchWithProject(project)
            openPicker(app, "a", "b")

            // Without any remote, the current branch is the only one the cleanup leaves out.
            onNodeWithTag(GitTags.BRANCH_CLEANUP).performClick()
            awaitTag(GitTags.branchToDelete("a"))
            awaitTag(GitTags.branchToDelete("b"))
            awaitNoTag(GitTags.branchToDelete(default))
            onNodeWithTag(GitTags.BRANCH_DELETE_CANCEL).performClick()
            awaitNoTag(GitTags.BRANCH_DELETE_DIALOG)
            awaitTag(GitTags.BRANCH_SEARCH)

            onNodeWithTag(GitTags.BRANCH_CLEANUP).performClick()
            awaitTag(GitTags.BRANCH_DELETE_DIALOG)
            onNodeWithTag(GitTags.BRANCH_DELETE_DIALOG).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(GitTags.BRANCH_DELETE_DIALOG)
            // Escape leaves the dialog, not the picker; the next Escape, back in the search field, closes the picker.
            awaitTag(GitTags.BRANCH_PICKER)
            onNodeWithTag(GitTags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(GitTags.BRANCH_PICKER)
            assertEquals(setOf(default, "a", "b"), localBranches())
        }

    @Test
    fun `a branch checked out in another worktree is kept with Git's reason, and the others are deleted`() =
        runComposeUiTest {
            project.commitAll()
            listOf("busy", "free").forEach { project.git("branch", it) }
            val worktree = temporaryDirectory("kotlin-editor-worktree")
            worktree.toFile().delete()
            project.git("worktree", "add", "-q", worktree.toString(), "busy")
            val app = launchWithProject(project)
            openPicker(app, "busy", "free")

            onNodeWithTag(GitTags.BRANCH_CLEANUP).performClick()
            awaitTag(GitTags.branchToDelete("busy"))
            onNodeWithTag(GitTags.BRANCH_DELETE_CONFIRM).performClick()

            // The dialog stays open with Git's reason, and only lists what is still there.
            awaitTag(GitTags.BRANCH_ERROR)
            onNodeWithTag(GitTags.BRANCH_ERROR).assertTextContains("busy", substring = true)
            awaitNoTag(GitTags.branchToDelete("free"))
            awaitTag(GitTags.branchToDelete("busy"))
            assertTrue("free" !in localBranches() && "busy" in localBranches())
            onNodeWithTag(GitTags.BRANCH_DELETE_CANCEL).performClick()
            awaitNoTag(GitTags.BRANCH_DELETE_DIALOG)
            project.git("worktree", "remove", "--force", worktree.toString())
        }

    @Test
    fun `a listed branch deleted by another program before the confirmation is not an error`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            listOf("a", "b").forEach { project.git("branch", it) }
            val app = launchWithProject(project)
            openPicker(app, "a", "b")
            onNodeWithTag(GitTags.BRANCH_CLEANUP).performClick()
            awaitTag(GitTags.branchToDelete("a"))

            project.git("branch", "-D", "a")
            onNodeWithTag(GitTags.BRANCH_DELETE_CONFIRM).performClick()
            awaitTag(GitTags.BRANCH_DONE)
            awaitNoTag(GitTags.BRANCH_ERROR)
            assertEquals(setOf(default), localBranches())
            assertTrue(!app.project.gitMessageIsError)
        }
}
