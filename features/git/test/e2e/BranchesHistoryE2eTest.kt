package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.HISTORY_PAGE
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Branches tool window: every branch, the history of the selected one, and the details of a commit. */
@OptIn(ExperimentalTestApi::class)
class BranchesHistoryE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun hashOf(ref: String) = project.git("rev-parse", ref).trim()

    private fun ComposeUiTest.openBranches() {
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
    }

    private fun ComposeUiTest.awaitTitle(ref: String) =
        awaitCondition {
            runCatching { onNodeWithTag(GitTags.HISTORY_TITLE).assertTextContains("History of $ref") }.isSuccess
        }

    @Test
    fun `the Branches window opens on the history of the current branch and shows a commit's files`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            project.file("src/Added.kt", "class Added\n")
            project.git("rm", "-q", "README.md")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "Add a class\n\nAnd remove the readme.")
            project.git("tag", "v1")
            val app = launchWithProject(project)
            awaitCondition { app.project.git.currentBranch == default }
            openBranches()

            awaitTitle(default)
            val head = hashOf("HEAD")
            awaitTag(GitTags.historyCommit(head))
            awaitTag(GitTags.historyCommit(hashOf("HEAD~1")))
            awaitTag(GitTags.HISTORY_END)
            onNodeWithTag(GitTags.HISTORY_END).assertTextContains("2 commits")
            // The newest commit carries its branch and its tag.
            onNodeWithTag(GitTags.historyCommit(head)).assertTextContains("v1", substring = true)

            onNodeWithTag(GitTags.historyCommit(head)).performClick()
            awaitTag(GitTags.HISTORY_DETAILS)
            awaitTag(GitTags.historyFile("src/Added.kt"))
            awaitTag(GitTags.historyFile("README.md"))
            awaitCondition {
                onAllNodes(hasText("And remove the readme.") and hasAnyAncestor(hasTestTag(GitTags.HISTORY_DETAILS)))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // The strip button hides it again.
            onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
            awaitNoTag(GitTags.BRANCHES_PANEL)
        }

    @Test
    fun `another local or remote branch shows its own history, and the filter narrows the list`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
            gitIn(remote, "init", "-q", "--bare")
            project.git("remote", "add", "origin", remote.toString())
            project.git("push", "-q", "-u", "origin", "HEAD")
            project.git("checkout", "-q", "-b", "feature/login")
            project.git("commit", "-q", "--allow-empty", "-m", "Login form")
            project.git("checkout", "-q", default)
            project.git("commit", "-q", "--allow-empty", "-m", "Only on the default branch")
            val app = launchWithProject(project)
            awaitCondition { app.project.git.branchInfos.size == 2 }
            openBranches()
            awaitTag(GitTags.historyBranch("origin/$default"))

            onNodeWithTag(GitTags.historyBranch("feature/login")).performClick()
            awaitTitle("feature/login")
            awaitTag(GitTags.historyCommit(hashOf("feature/login")))
            awaitNoTag(GitTags.historyCommit(hashOf(default)))

            // The remote branch has only the first commit: it was pushed before the others.
            onNodeWithTag(GitTags.historyBranch("origin/$default")).performClick()
            awaitTitle("origin/$default")
            awaitTag(GitTags.historyCommit(hashOf("origin/$default")))
            awaitTag(GitTags.HISTORY_END)
            onNodeWithTag(GitTags.HISTORY_END).assertTextContains("1 commit")

            onNodeWithTag(GitTags.HISTORY_BRANCH_SEARCH).performTextInput("login")
            awaitNoTag(GitTags.historyBranch(default))
            awaitNoTag(GitTags.historyBranch("origin/$default"))
            awaitTag(GitTags.historyBranch("feature/login"))
        }

    @Test
    fun `Show History of a pinned branch opens the window on that branch`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchWithProject(project)
            awaitCondition {
                app.project.git.branchInfos
                    .any { it.name == "feature" }
            }
            onNodeWithTag(GitTags.GIT_BRANCH).performClick()
            awaitTag(GitTags.BRANCH_PICKER)
            onNodeWithTag(GitTags.branchPin("feature")).performClick()
            onNodeWithTag(GitTags.BRANCH_SEARCH).performClick()
            onNodeWithTag(GitTags.BRANCH_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitTag(GitTags.pinnedBranch("feature"))

            onNodeWithTag(GitTags.pinnedBranchMenu("feature")).performClick()
            awaitTag(GitTags.branchMenuHistory("feature")).performClick()
            awaitTag(GitTags.BRANCHES_PANEL)
            awaitTitle("feature")
            awaitTag(GitTags.historyCommit(hashOf("feature")))
        }

    @Test
    fun `a long history is loaded page by page while scrolling`() =
        runComposeUiTest {
            project.commitAll()
            val total = HISTORY_PAGE * 2 + 20
            repeat(total - 1) { project.git("commit", "-q", "--allow-empty", "-m", "commit $it") }
            val app = launchWithProject(project)
            awaitCondition { app.project.git.currentBranch != null }
            openBranches()
            awaitCondition { app.project.git.history.commits.size == HISTORY_PAGE }
            assertTrue(!app.project.git.history.complete)

            val list = onNode(hasScrollToIndexInHistory())
            // Each page is asked for once the end of the previous one is in view.
            repeat(3) {
                awaitCondition { !app.project.git.history.loading }
                list.performScrollToIndex(app.project.git.history.commits.size - 1)
            }
            awaitCondition { app.project.git.history.complete }
            list.performScrollToIndex(total)
            awaitTag(GitTags.HISTORY_END)
            onNodeWithTag(GitTags.HISTORY_END).assertTextContains("$total commits")
            // Each commit once, newest first, down to the first one.
            val commits = app.project.git.history.commits
            assertEquals(total, commits.map { it.hash }.distinct().size)
            assertEquals("init", commits.last().subject)
        }

    @Test
    fun `a branch deleted by another program says so instead of loading forever`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "doomed")
            val app = launchWithProject(project)
            awaitCondition {
                app.project.git.branchInfos
                    .any { it.name == "doomed" }
            }
            openBranches()
            onNodeWithTag(GitTags.historyBranch("doomed")).performClick()
            awaitTitle("doomed")
            awaitTag(GitTags.historyCommit(hashOf("doomed")))

            project.git("branch", "-D", "doomed")
            onNodeWithTag(GitTags.HISTORY_REFRESH).performClick()
            awaitTag(GitTags.HISTORY_ERROR)
            awaitNoTag(GitTags.HISTORY_LOADING)
            // The branch left the list, and another one can be shown.
            awaitNoTag(GitTags.historyBranch("doomed"))
        }

    @Test
    fun `outside a repository the window says so`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitCondition { !app.project.git.isRepository }
            openBranches()
            awaitCondition {
                onAllNodes(hasText("Not a Git repository") and hasAnyAncestor(hasTestTag(GitTags.BRANCHES_PANEL)))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
}

/** The scrollable commit list of the Branches window. */
internal fun hasScrollToIndexInHistory() =
    hasScrollToIndexAction() and
        hasAnyAncestor(hasTestTag(GitTags.BRANCHES_PANEL)) and
        hasAnyChild(hasTestTagPrefix("history.commit:"))
