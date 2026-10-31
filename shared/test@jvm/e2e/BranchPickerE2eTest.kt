package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The branch picker of the toolbar: search, pin several branches, and the distance from the remote. */
@OptIn(ExperimentalTestApi::class)
class BranchPickerE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extras.add(it) }

    private fun remoteWithOneNewCommit(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = temporaryDirectory("kotlin-editor-remote")
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        project.git("branch", "feature/login")
        project.git("branch", "feature/search-ui")
        val clone = temporaryDirectory("kotlin-editor-clone")
        gitIn(clone, "clone", "-q", remote.toString(), ".")
        clone.resolve("remote.txt").writeText("from the remote\n")
        gitIn(clone, "add", "-A")
        gitIn(clone, "commit", "-q", "-m", "remote commit")
        gitIn(clone, "push", "-q")
        project.git("fetch", "-q")
        return default
    }

    @Test
    fun `the toolbar shows how far the current branch is behind its remote`() =
        runComposeUiTest {
            val default = remoteWithOneNewCommit()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.any { it.name == default && it.behind == 1 } }
            // At a glance, without opening anything: the toolbar shows the current branch behind by one commit.
            awaitTag(ItemTags.branchBehind(default), unmerged = true)
        }

    @Test
    fun `the branch picker searches local branches and checks one out`() =
        runComposeUiTest {
            remoteWithOneNewCommit()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature/search-ui" } }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            awaitTag(ItemTags.branchItem("feature/login"))
            awaitTag(ItemTags.branchItem("feature/search-ui"))

            onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("search")
            awaitNoTag(ItemTags.branchItem("feature/login"))
            awaitTag(ItemTags.branchItem("feature/search-ui"))
            onNodeWithTag(ItemTags.branchItem("feature/search-ui")).performClick()
            awaitNoTag(Tags.BRANCH_PICKER)
            awaitCondition { app.project.gitBranch == "feature/search-ui" }
            assertEquals("Switched to feature/search-ui", app.project.gitMessage)
        }

    @Test
    fun `a branch without an upstream is reported as having no remote`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "local-only")
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.any { it.name == "local-only" } }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            awaitTag(ItemTags.branchItem("local-only"))
            onAllNodesWithText("no remote tracking branch").onFirst().assertIsDisplayed()
            val branch = app.project.gitBranchInfos.single { it.name == "local-only" }
            assertTrue(branch.upstream == null && branch.behind == 0)
        }

    @Test
    fun `several branches can be pinned in the toolbar and unpinned`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature/login")
            project.git("branch", "feature/search-ui")
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranchInfos.size == 3 }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)

            onNodeWithTag(ItemTags.branchSelect("feature/login")).performClick()
            onNodeWithTag(ItemTags.branchSelect("feature/search-ui")).performClick()
            awaitCondition {
                app.project.selectedBranches == setOf("feature/login", "feature/search-ui")
            }
            awaitTag(Tags.BRANCH_SELECTION_COUNT)

            // Typing filters the list; a query without matches says so instead of showing an empty panel.
            onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("does-not-exist")
            awaitTag(Tags.BRANCH_NO_MATCH)
            onNodeWithTag(Tags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(Tags.BRANCH_PICKER)

            // Both pinned branches are chips in the toolbar; closing one unpins it.
            awaitTag(ItemTags.pinnedBranch("feature/login"))
            awaitTag(ItemTags.pinnedBranch("feature/search-ui"))
            onNodeWithTag(ItemTags.unpinnedBranch("feature/login")).performClick()
            awaitCondition { app.project.selectedBranches == setOf("feature/search-ui") }
            awaitNoTag(ItemTags.pinnedBranch("feature/login"))

            // A chip checks its branch out, like its row in the picker.
            onNodeWithTag(ItemTags.pinnedBranch("feature/search-ui")).performClick()
            awaitCondition { app.project.gitBranch == "feature/search-ui" }
        }
}
