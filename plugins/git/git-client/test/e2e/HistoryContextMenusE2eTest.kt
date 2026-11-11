package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import org.example.kotlineditor.ui.TextClipboard
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The right-click menus of the branches and the commits of the Branches window. */
@OptIn(ExperimentalTestApi::class)
class HistoryContextMenusE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun rightClick(node: SemanticsNodeInteraction) = node.performMouseInput { rightClick() }

    private fun ComposeUiTest.openBranches(): String {
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
        return default
    }

    private fun ComposeUiTest.openBranchMenu(name: String) {
        rightClick(awaitTag(GitTags.historyBranch(name)))
        awaitTag(GitTags.BRANCH_CONTEXT_MENU)
    }

    // Clicking outside the menu dismisses it, like the user does to cancel.
    private fun ComposeUiTest.closeMenu() {
        onNodeWithTag(GitTags.HISTORY_TITLE).performClick()
        awaitNoTag(GitTags.BRANCH_CONTEXT_MENU)
    }

    private fun ComposeUiTest.item(action: String) = awaitTag(GitTags.historyMenuItem(action))

    private fun branches(): List<String> =
        project.git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    @Test
    fun `right clicking a branch checks it out, and the current branch cannot be checked out or deleted`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            val app = launchWithProject(project)
            awaitCondition { app.project.git.currentBranch == default }
            openBranches()

            openBranchMenu(default)
            item("checkout").assertIsNotEnabled()
            onNodeWithTag(GitTags.historyMenuItem("delete")).assertIsNotEnabled()

            // Clicking outside closes the menu without doing anything.
            closeMenu()
            assertEquals(default, project.git("rev-parse", "--abbrev-ref", "HEAD").trim())

            openBranchMenu("feature")
            item("checkout").assertIsEnabled()
            item("checkout").performClick()
            awaitCondition { app.project.git.currentBranch == "feature" }
            assertEquals("feature", project.git("rev-parse", "--abbrev-ref", "HEAD").trim())
        }

    @Test
    fun `deleting a branch from its menu asks again, and nothing is deleted until it is confirmed`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            project.git("branch", "other")
            val app = launchWithProject(project)
            awaitCondition { "feature" in app.project.git.branches }
            openBranches()

            openBranchMenu("feature")
            item("delete").performClick() // first click: asks
            onNodeWithTag(GitTags.historyMenuItem("delete")).assertExists()
            assertTrue("feature" in branches())
            closeMenu() // cancelled
            assertTrue("feature" in branches())

            openBranchMenu("feature")
            item("delete").performClick()
            item("delete").performClick() // second click: confirms
            awaitNoTag(GitTags.historyBranch("feature"))
            assertEquals(false, "feature" in branches())
            assertTrue("other" in branches())
        }

    @Test
    fun `a branch menu copies the branch name, a commit menu copies its hash`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchWithProject(project)
            awaitCondition { "feature" in app.project.git.branches }
            val default = openBranches()
            val head = project.git("rev-parse", "HEAD").trim()

            openBranchMenu("feature")
            item("copyName").performClick()
            assertEquals("feature", TextClipboard.get())

            awaitTag(GitTags.historyBranch(default)).performClick()
            rightClick(awaitTag(GitTags.historyCommit(head)))
            awaitTag(GitTags.COMMIT_CONTEXT_MENU)
            item("copyHash").performClick()
            assertEquals(head, TextClipboard.get())
        }

    @Test
    fun `the menu of a remote branch only offers what applies to it`() =
        runComposeUiTest {
            project.commitAll()
            val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
            gitIn(remote, "init", "-q", "--bare")
            project.git("remote", "add", "origin", remote.toString())
            project.git("push", "-q", "-u", "origin", "HEAD")
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            val app = launchWithProject(project)
            awaitCondition { app.project.git.currentBranch == default }
            openBranches()

            openBranchMenu("origin/$default")
            item("delete").assertIsNotEnabled()
            item("push").assertIsNotEnabled()
            item("copyName").performClick()
            assertEquals("origin/$default", TextClipboard.get())
        }
}
