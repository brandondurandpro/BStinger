package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.HISTORY_PAGE
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The search of the Branches window, and its minimize button. */
@OptIn(ExperimentalTestApi::class)
class BranchesSearchE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun hashOf(ref: String) = project.git("rev-parse", ref).trim()

    // Commits by two authors; one mentions the login only in its body.
    private fun history(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        project.git("-c", "user.name=Alice Martin", "commit", "-q", "--allow-empty", "-m", "Fix the login form")
        project.git("commit", "-q", "--allow-empty", "-m", "Refactor sessions", "-m", "Needed by the LOGIN page.")
        project.git("commit", "-q", "--allow-empty", "-m", "Update the readme")
        return default
    }

    private fun ComposeUiTest.openBranches(app: E2eApp) {
        awaitCondition { app.project.gitBranch != null }
        onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
        awaitTag(Tags.BRANCHES_PANEL)
        awaitTag(ItemTags.historyCommit(hashOf("HEAD")))
    }

    private fun ComposeUiTest.awaitFooter(tag: String, text: String) =
        awaitCondition { runCatching { onNodeWithTag(tag).assertTextContains(text) }.isSuccess }

    @Test
    fun `the search narrows the history to the commits whose message, author or hash match`() =
        runComposeUiTest {
            history()
            val app = launchWithProject(project)
            openBranches(app)
            val fix = hashOf("HEAD~2")
            val refactor = hashOf("HEAD~1")
            val readme = hashOf("HEAD")

            // The subject of one, the body of the other: both are found, the rest is not.
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("login")
            awaitNoTag(ItemTags.historyCommit(readme))
            awaitTag(ItemTags.historyCommit(fix))
            awaitTag(ItemTags.historyCommit(refactor))
            awaitFooter(Tags.HISTORY_END, "2 commits match \"login\"")

            onNodeWithTag(Tags.HISTORY_SEARCH_CLEAR).performClick()
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("alice")
            awaitNoTag(ItemTags.historyCommit(refactor))
            awaitTag(ItemTags.historyCommit(fix))

            onNodeWithTag(Tags.HISTORY_SEARCH_CLEAR).performClick()
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput(readme.take(9))
            awaitNoTag(ItemTags.historyCommit(fix))
            awaitTag(ItemTags.historyCommit(readme))
        }

    @Test
    fun `a search without result says so, and clearing it brings the whole history back`() =
        runComposeUiTest {
            history()
            val app = launchWithProject(project)
            openBranches(app)

            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("nothing like this")
            awaitTag(Tags.HISTORY_NO_MATCH)
            awaitFooter(Tags.HISTORY_NO_MATCH, "No commit matches \"nothing like this\"")
            awaitNoTag(ItemTags.historyCommit(hashOf("HEAD")))

            // Escape in the field clears the search.
            onNodeWithTag(Tags.HISTORY_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitTag(ItemTags.historyCommit(hashOf("HEAD")))
            awaitFooter(Tags.HISTORY_END, "4 commits")
            assertEquals("", app.project.history.commitQuery)
            // The window is still open: Escape only cleared the search.
            awaitTag(Tags.BRANCHES_PANEL)
        }

    @Test
    fun `the search follows the selected branch, and only the last typed search is shown`() =
        runComposeUiTest {
            history()
            project.git("checkout", "-q", "-b", "feature", "HEAD~2")
            project.git("commit", "-q", "--allow-empty", "-m", "Login on the feature branch")
            val feature = hashOf("HEAD")
            project.git("checkout", "-q", "-")
            val app = launchWithProject(project)
            openBranches(app)

            // Typed key by key: every pause could start a search, the last one wins.
            "login".forEach { onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput(it.toString()) }
            awaitFooter(Tags.HISTORY_END, "2 commits match \"login\"")
            onNodeWithTag(ItemTags.historyBranch("feature")).performClick()
            awaitTag(ItemTags.historyCommit(feature))
            // The fix of the login is an ancestor of the feature branch; the refactor is not.
            awaitTag(ItemTags.historyCommit(hashOf("HEAD~2")))
            awaitNoTag(ItemTags.historyCommit(hashOf("HEAD~1")))
            assertEquals("login", app.project.history.appliedQuery)
        }

    @Test
    fun `a search with many matches is loaded page by page`() =
        runComposeUiTest {
            project.commitAll()
            val total = HISTORY_PAGE + 30
            repeat(total) { project.git("commit", "-q", "--allow-empty", "-m", "match $it") }
            repeat(20) { project.git("commit", "-q", "--allow-empty", "-m", "other $it") }
            val app = launchWithProject(project)
            openBranches(app)

            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("match")
            val history = app.project.history
            awaitCondition { history.appliedQuery == "match" && history.commits.size == HISTORY_PAGE }
            val list = onNode(hasScrollToIndexInHistory())
            awaitCondition { !app.project.history.loading }
            list.performScrollToIndex(app.project.history.commits.size - 1)
            awaitCondition { app.project.history.complete }
            val commits = app.project.history.commits
            assertEquals(total, commits.size)
            assertTrue(commits.all { it.subject.startsWith("match") })
        }

    @Test
    fun `the minimize button hides the window and keeps its search and selection`() =
        runComposeUiTest {
            history()
            val app = launchWithProject(project)
            openBranches(app)
            onNodeWithTag(Tags.HISTORY_SEARCH).performTextInput("login")
            val fix = hashOf("HEAD~2")
            awaitTag(ItemTags.historyCommit(fix)).performClick()
            awaitTag(Tags.HISTORY_DETAILS)

            onNodeWithTag(Tags.HISTORY_HIDE).performClick()
            awaitNoTag(Tags.BRANCHES_PANEL)
            onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
            awaitTag(Tags.HISTORY_DETAILS)
            awaitTag(ItemTags.historyCommit(fix))
            awaitNoTag(ItemTags.historyCommit(hashOf("HEAD")))
            assertEquals("login", app.project.history.commitQuery)
        }
}
