package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLOW_LINK_MS = 400L

/** The branch and commit menus when the network or the server fails: an action runs once, whatever happens. */
@OptIn(ExperimentalTestApi::class)
class HistoryActionsChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
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

    private fun ComposeUiTest.launchOnHistory(): E2eApp {
        val default = currentBranch()
        val app = launchWithProject(project, chaos = true)
        awaitCondition { app.project.git.currentBranch == default }
        onNodeWithTag(AppTags.TOOL_BRANCHES).performClick()
        awaitTag(GitTags.BRANCHES_PANEL)
        awaitTag(GitTags.historyCommit(head()))
        return app
    }

    private fun ComposeUiTest.commitMenu(hash: String, action: String): SemanticsNodeInteraction {
        rightClick(awaitTag(GitTags.historyCommit(hash)))
        awaitTag(GitTags.COMMIT_CONTEXT_MENU)
        return awaitTag(GitTags.historyMenuItem(action))
    }

    private fun ComposeUiTest.typeNameAndConfirm(name: String) {
        awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput(name)
        awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
    }

    private fun commitCount() = project.git("rev-list", "--count", "--all").trim().toInt()

    @Test
    fun `a cherry-pick whose answer is lost is made once`() =
        runComposeUiTest {
            project.commitAll()
            val default = currentBranch()
            project.git("checkout", "-q", "-b", "side")
            val picked = commitFile("side.txt", "side\n", "side work")
            project.git("checkout", "-q", default)
            commitFile("main.txt", "main\n", "main work")
            val app = launchOnHistory()
            awaitCondition { "side" in app.project.git.branches }
            awaitTag(GitTags.historyBranch("side")).performClick()
            val before = commitCount()

            // The cherry-pick is made, then the connection breaks before its answer: the retry must not apply it twice.
            app.network.dropFromServer = true
            commitMenu(picked, "cherryPick").performClick()
            awaitCondition {
                project.root
                    .resolve("side.txt")
                    .toFile()
                    .exists()
            }
            app.network.cutAll()
            app.network.dropFromServer = false

            awaitReconnected()
            awaitCondition {
                app.project.git.message
                    ?.isNotBlank() == true &&
                    !app.project.git.busy
            }
            assertEquals(before + 1, commitCount())
            assertEquals(
                "side\n",
                project.root
                    .resolve("side.txt")
                    .toFile()
                    .readText(),
            )
        }

    @Test
    fun `a tag whose answer is lost is created once and the dialog reports no error`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchOnHistory()

            app.network.dropFromServer = true
            commitMenu(head(), "newTag").performClick()
            typeNameAndConfirm("v1")
            awaitCondition { project.git("tag").trim() == "v1" }
            app.network.cutAll()
            app.network.dropFromServer = false

            awaitReconnected()
            // Not "a tag named v1 already exists": the retry got the answer of the first run.
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals("v1", project.git("tag").trim())
        }

    @Test
    fun `a branch created while the network is down is created once it is back`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchOnHistory()
            val default = currentBranch()

            app.network.goDown()
            awaitReconnecting()
            commitMenu(head(), "newBranch").performClick()
            typeNameAndConfirm("later")
            // Nothing pretends to be done while the link is down.
            assertEquals(default, currentBranch())
            assertTrue(project.git("branch", "--list", "later").isBlank())

            app.network.comeBack()
            awaitReconnected()
            awaitNoTag(GitTags.HISTORY_DIALOG)
            awaitCondition { app.project.git.currentBranch == "later" }
            assertEquals("later", currentBranch())
        }

    @Test
    fun `a slow link disables the dialog while its action runs, and the action is made once`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchOnHistory()

            commitMenu(head(), "newTag").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_INPUT).performTextInput("slow")
            app.network.latencyMs = SLOW_LINK_MS
            awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
            // While the request is in flight, the dialog cannot be confirmed a second time.
            awaitCondition {
                runCatching {
                    onNodeWithTag(
                        GitTags.HISTORY_DIALOG_CONFIRM,
                    ).assertIsNotEnabled()
                }.isSuccess
            }
            awaitNoTag(GitTags.HISTORY_DIALOG)
            assertEquals("slow", project.git("tag").trim())
            app.network.latencyMs = 0
        }

    @Test
    fun `a hard reset is made once after the server restarts, and the window works again`() =
        runComposeUiTest {
            project.commitAll()
            val first = head()
            commitFile("two.txt", "two\n", "second")
            val app = launchOnHistory()

            app.process.restart()
            awaitReconnected()
            commitMenu(first, "resetHard").performClick()
            awaitTag(GitTags.HISTORY_DIALOG_CONFIRM).performClick()
            awaitCondition { head() == first }
            assertTrue(
                !project.root
                    .resolve("two.txt")
                    .toFile()
                    .exists(),
            )
        }
}
