package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
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

/**
 * What the branch and commit menus must not change: opening them, copying and cancelling touch nothing, and each
 * action moves only the refs it is for, on a project with the files real projects have.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryActionsSideEffectsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }

    private var launched: E2eApp? = null

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
    }

    private fun head() = project.git("rev-parse", "HEAD").trim()

    private fun currentBranch() = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()

    private fun rightClick(node: SemanticsNodeInteraction) = node.performMouseInput { rightClick() }

    // The items of the menus are disabled while a Git action runs: a menu opened before its end would click nothing.
    private fun ComposeUiTest.awaitGitIdle() {
        val app = launched ?: return
        awaitCondition { !app.project.git.busy }
    }

    private fun ComposeUiTest.launchOnHistory(): E2eApp {
        val default = currentBranch()
        val app = launchWithProject(project)
        launched = app
        awaitCondition { app.project.git.currentBranch == default }
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
        awaitTag(GitTags.historyCommit(head()))
        return app
    }

    private fun ComposeUiTest.commitMenu(hash: String, action: String): SemanticsNodeInteraction {
        awaitGitIdle()
        rightClick(awaitTag(GitTags.historyCommit(hash)))
        awaitTag(GitTags.COMMIT_CONTEXT_MENU)
        return awaitTag(GitTags.historyMenuItem(action))
    }

    private fun ComposeUiTest.branchMenu(name: String, action: String): SemanticsNodeInteraction {
        awaitGitIdle()
        rightClick(awaitTag(GitTags.historyBranch(name)))
        awaitTag(GitTags.BRANCH_CONTEXT_MENU)
        return awaitTag(GitTags.historyMenuItem(action))
    }

    private fun ComposeUiTest.awaitEnabled(tag: String): SemanticsNodeInteraction {
        awaitCondition { runCatching { onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        return onNodeWithTag(tag)
    }

    private fun ComposeUiTest.typeName(name: String) {
        awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput(name)
        awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
        awaitNoTag(GitTags.HISTORY_DIALOG)
    }

    @Test
    fun `opening the menus, copying and cancelling a dialog change nothing`() =
        runComposeUiTest {
            val default = currentBranch()
            val before = DiskSnapshot.take(project)
            launchOnHistory()

            branchMenu(default, "copyName").performClick()
            commitMenu(head(), "copyHash").performClick()
            commitMenu(head(), "newTag").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_CANCEL).performClick()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            commitMenu(head(), "resetHard").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_CANCEL).performClick()
            awaitNoTag(GitTags.HISTORY_DIALOG)

            before.assertUnchanged(project)
        }

    @Test
    fun `tagging a commit adds one tag and nothing else`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchOnHistory()

            commitMenu(head(), "newTag").performClick()
            typeName("v1")

            awaitCondition { project.git("tag").trim() == "v1" }
            before.assertUnchanged(project, exceptRefs = setOf("refs/tags/v1"))
        }

    @Test
    fun `a new branch creates only that branch, and deleting it again leaves the repository as it was`() =
        runComposeUiTest {
            val default = currentBranch()
            val before = DiskSnapshot.take(project)
            val app = launchOnHistory()

            branchMenu(default, "newBranch").performClick()
            typeName("fresh")
            awaitCondition { app.project.git.currentBranch == "fresh" }
            before.assertUnchanged(project, exceptRefs = setOf("refs/heads/fresh"), headMoves = true)

            app.project.git.checkoutBranch(default)
            awaitCondition { app.project.git.currentBranch == default }
            // Delete stays disabled until the checkout is over and the branches are read again: a click before
            // that does nothing, and the menu stays open on a branch that is never deleted.
            branchMenu("fresh", "delete")
            awaitEnabled(GitTags.historyMenuItem("delete")).performClick()
            awaitEnabled(GitTags.historyMenuItem("delete")).performClick()
            awaitNoTag(GitTags.historyBranch("fresh"))
            before.assertUnchanged(project)
        }

    @Test
    fun `renaming a branch moves only its two refs`() =
        runComposeUiTest {
            project.git("branch", "feature")
            val before = DiskSnapshot.take(project)
            val app = launchOnHistory()
            awaitCondition { "feature" in app.project.git.branches }

            branchMenu("feature", "rename").performClick()
            typeName("renamed")

            awaitCondition { "renamed" in app.project.git.branches }
            before.assertUnchanged(project, exceptRefs = setOf("refs/heads/feature", "refs/heads/renamed"))
        }

    @Test
    fun `a cherry-pick changes the picked files and the current branch only`() =
        runComposeUiTest {
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            project.file("picked.txt", "picked\n")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "picked")
            val picked = head()
            project.git("checkout", "-q", default)
            project.file("other.txt", "other\n")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "other")
            val before = DiskSnapshot.take(project)
            val app = launchOnHistory()
            awaitCondition { "side" in app.project.git.branches }

            awaitTag(GitTags.historyBranch("side")).performClick()
            commitMenu(picked, "cherryPick").performClick()
            awaitCondition {
                project.root
                    .resolve("picked.txt")
                    .toFile()
                    .exists()
            }

            before.assertUnchanged(
                project,
                except = setOf("picked.txt"),
                exceptRefs = setOf("refs/heads/$default"),
                headMoves = true,
            )
        }
}
