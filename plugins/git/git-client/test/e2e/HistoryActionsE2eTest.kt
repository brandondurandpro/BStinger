package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The actions of the branch and commit menus of the Branches window that change the repository. */
@OptIn(ExperimentalTestApi::class)
class HistoryActionsE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    private var launched: E2eApp? = null

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun head() = project.git("rev-parse", "HEAD").trim()

    private fun currentBranch() = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()

    private fun commitFile(name: String, text: String, message: String): String {
        project.file(name, text)
        project.git("add", "-A")
        project.git("commit", "-q", "-m", message)
        return head()
    }

    private fun rightClick(node: SemanticsNodeInteraction) = node.performMouseInput { rightClick() }

    // The items of the menus are disabled while a Git action runs: a menu opened before its end would click nothing.
    private fun ComposeUiTest.awaitGitIdle() {
        val app = launched ?: return
        awaitCondition { !app.project.git.busy }
    }

    private fun ComposeUiTest.openBranches() {
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
    }

    private fun ComposeUiTest.branchMenu(name: String, action: String): SemanticsNodeInteraction {
        awaitGitIdle()
        rightClick(awaitTag(GitTags.historyBranch(name)))
        awaitTag(GitTags.BRANCH_CONTEXT_MENU)
        return awaitTag(GitTags.historyMenuItem(action))
    }

    private fun ComposeUiTest.commitMenu(hash: String, action: String): SemanticsNodeInteraction {
        awaitGitIdle()
        rightClick(awaitTag(GitTags.historyCommit(hash)))
        awaitTag(GitTags.COMMIT_CONTEXT_MENU)
        return awaitTag(GitTags.historyMenuItem(action))
    }

    private fun ComposeUiTest.confirmDialog() = awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()

    private fun ComposeUiTest.launchOnHistory(): E2eApp {
        val default = currentBranch()
        val app = launchWithProject(project)
        launched = app
        awaitCondition { app.project.git.currentBranch == default }
        openBranches()
        awaitTag(GitTags.historyCommit(head()))
        return app
    }

    @Test
    fun `New branch from here creates and checks out a branch, refuses a taken name and can be cancelled`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "taken")
            val default = currentBranch()
            val app = launchOnHistory()

            branchMenu(default, "newBranch").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("taken")
            confirmDialog()
            awaitTag(GitTags.HISTORY_DIALOG_ERROR).assertTextContains("already exists", substring = true)
            assertEquals(default, currentBranch())

            onNodeWithTag(GitTags.HISTORY_DIALOG_CANCEL).performClick()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals(default, currentBranch())

            branchMenu(default, "newBranch").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).assertIsNotEnabled() // a name is required
            onNodeWithTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("fresh")
            confirmDialog()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            awaitCondition { app.project.git.currentBranch == "fresh" }
            assertEquals("fresh", currentBranch())
        }

    @Test
    fun `Rename renames a branch and keeps the others`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchOnHistory()
            awaitCondition { "feature" in app.project.git.branches }

            branchMenu("feature", "rename").performClick()
            val input = awaitTag(GitTags.HISTORY_DIALOG_INPUT)
            input.assertTextContains("feature")
            input.performTextInput("feature-v2") // the name is selected: typing replaces it
            confirmDialog()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            awaitCondition { project.git("branch").contains("v2") }
            awaitTag(GitTags.historyBranch("feature-v2"))
            assertTrue("feature-v2" in project.git("branch").trim())
            assertFalse(project.git("branch", "--list", "feature").isNotBlank())
        }

    @Test
    fun `a remote branch is checked out as a local branch that tracks it`() =
        runComposeUiTest {
            project.commitAll()
            val default = currentBranch()
            val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
            gitIn(remote, "init", "-q", "--bare")
            project.git("remote", "add", "origin", remote.toString())
            project.git("branch", "feature")
            project.git("push", "-q", "origin", default, "feature")
            project.git("branch", "-D", "feature")
            project.git("fetch", "-q")
            val app = launchOnHistory()
            awaitCondition { "origin/feature" in app.project.git.history.remoteBranches }

            branchMenu("origin/feature", "checkoutRemote").performClick()
            awaitCondition { app.project.git.currentBranch == "feature" }
            assertEquals("origin/feature", project.git("rev-parse", "--abbrev-ref", "feature@{upstream}").trim())
        }

    @Test
    fun `Merge into current brings a branch in, and the current branch cannot be merged into itself`() =
        runComposeUiTest {
            project.commitAll()
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            commitFile("side.txt", "side\n", "side work")
            project.git("checkout", "-q", default)
            val app = launchOnHistory()
            awaitCondition { "side" in app.project.git.branches }

            branchMenu(default, "merge").assertIsNotEnabled()
            onNodeWithTag(GitTags.HISTORY_TITLE).performClick()
            branchMenu("side", "merge").assertIsEnabled().performClick()
            awaitCondition { project.root.resolve("side.txt").exists() }
            assertEquals("side\n", project.root.resolve("side.txt").readText())
            assertEquals(default, currentBranch())
        }

    @Test
    fun `a merge that conflicts reports it and leaves the conflict to resolve`() =
        runComposeUiTest {
            project.file("c.txt", "base\n")
            project.commitAll()
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            commitFile("c.txt", "side\n", "side change")
            project.git("checkout", "-q", default)
            commitFile("c.txt", "main\n", "main change")
            val app = launchOnHistory()
            awaitCondition { "side" in app.project.git.branches }

            branchMenu("side", "merge").performClick()
            awaitCondition {
                app.project.git.message
                    ?.contains("conflicts") == true
            }
            assertFalse(app.project.git.messageIsError)
            assertTrue(project.git("diff", "--name-only", "--diff-filter=U").contains("c.txt"))
        }

    @Test
    fun `Rebase asks first, and cancelling changes nothing`() =
        runComposeUiTest {
            project.commitAll()
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            commitFile("side.txt", "side\n", "side work")
            project.git("checkout", "-q", default)
            commitFile("main.txt", "main\n", "main work")
            project.git("checkout", "-q", "side")
            val app = launchOnHistory()
            awaitCondition { app.project.git.currentBranch == "side" }
            val before = head()

            branchMenu(default, "rebase").performClick()
            awaitTag(GitTags.HISTORY_DIALOG)
            onNodeWithTag(GitTags.HISTORY_DIALOG_CANCEL).performClick()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals(before, head())

            branchMenu(default, "rebase").performClick()
            confirmDialog()
            // HEAD moves first to the default branch, then to the replayed commit: only the end of the action counts.
            awaitCondition { !app.project.git.busy && head() != before }
            assertTrue(project.root.resolve("main.txt").exists() && project.root.resolve("side.txt").exists())
            // Rebased onto the default branch: it is now the parent of the branch's commit.
            assertEquals(project.git("rev-parse", default).trim(), project.git("rev-parse", "HEAD~1").trim())
        }

    @Test
    fun `the commit menu checks out a commit detached, tags it and branches from it`() =
        runComposeUiTest {
            project.commitAll()
            val first = head()
            commitFile("two.txt", "two\n", "second")
            val default = currentBranch()
            val app = launchOnHistory()

            commitMenu(first, "newTag").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("v1")
            confirmDialog()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals(first, project.git("rev-parse", "v1^{commit}").trim())

            commitMenu(first, "newBranch").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("from-first")
            confirmDialog()
            awaitCondition { app.project.git.currentBranch == "from-first" }
            assertEquals(first, head())

            onNodeWithTag(GitTags.historyBranch(default)).performClick()
            commitMenu(first, "checkoutCommit").performClick()
            awaitCondition { project.git("rev-parse", "--abbrev-ref", "HEAD").trim() == "HEAD" }
            assertEquals(first, head())
        }

    @Test
    fun `Cherry-pick and Revert apply and undo a commit, and are disabled for a merge commit`() =
        runComposeUiTest {
            project.commitAll()
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            val picked = commitFile("side.txt", "side\n", "side work")
            project.git("checkout", "-q", default)
            commitFile("main.txt", "main\n", "main work") // so that the cherry-pick makes a different commit
            val app = launchOnHistory()
            awaitCondition { "side" in app.project.git.branches }

            onNodeWithTag(GitTags.historyBranch("side")).performClick()
            commitMenu(picked, "cherryPick").performClick()
            awaitCondition { project.root.resolve("side.txt").exists() }
            // The file is there before its commit: HEAD is the picked commit only once the action is over.
            awaitGitIdle()
            awaitTag(GitTags.historyBranch(default)).performClick()
            val applied = head()
            assertTrue(applied != picked)
            awaitTag(GitTags.historyCommit(applied))
            commitMenu(applied, "revert").performClick()
            awaitCondition { !project.root.resolve("side.txt").exists() }
            // The file goes before the commit of the revert: Git run now would find it as a local change.
            awaitGitIdle()

            project.git("merge", "-q", "--no-ff", "-m", "merge again", "side")
            val again = head()
            onNodeWithTag(GitTags.HISTORY_REFRESH).performClick()
            commitMenu(again, "cherryPick").assertIsNotEnabled()
            onNodeWithTag(GitTags.historyMenuItem("revert")).assertIsNotEnabled()
        }

    @Test
    fun `Reset soft keeps the changes staged, and a hard reset asks first`() =
        runComposeUiTest {
            project.commitAll()
            val first = head()
            commitFile("two.txt", "two\n", "second")
            val second = head()
            val app = launchOnHistory()

            commitMenu(first, "resetSoft").performClick()
            awaitCondition { head() == first }
            awaitGitIdle()
            assertEquals("A  two.txt", project.git("status", "--porcelain").trim()) // soft: still staged
            project.git("reset", "-q", "--hard", second)
            onNodeWithTag(GitTags.HISTORY_REFRESH).performClick()

            commitMenu(first, "resetHard").performClick()
            awaitTag(GitTags.HISTORY_DIALOG)
            onNodeWithTag(GitTags.HISTORY_DIALOG_CANCEL).performClick()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals(second, head()) // cancelled: nothing moved

            commitMenu(first, "resetHard").performClick()
            confirmDialog()
            awaitCondition { head() == first }
            assertFalse(project.root.resolve("two.txt").exists())
            awaitCondition {
                app.project.git.history.commits
                    .firstOrNull()
                    ?.hash == first
            }
        }
}
