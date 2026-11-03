package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Pull button of the toolbar while the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class QuickPullChaosE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extras.add(it) }

    // The project tracks a bare remote that has one commit more than the project. Returns the default branch and it.
    private fun projectBehindItsRemote(): Pair<String, Path> {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = temporaryDirectory("kotlin-editor-remote")
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        val clone = temporaryDirectory("kotlin-editor-clone")
        gitIn(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
        clone.resolve("remote.txt").writeText("from the remote\n")
        gitIn(clone, "add", "-A")
        gitIn(clone, "commit", "-q", "-m", "remote work")
        gitIn(clone, "push", "-q")
        return default to remote
    }

    private fun ComposeUiTest.awaitSyncStatus(text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(GitTags.BRANCH_SYNC_STATUS).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }

    private fun head() = project.git("rev-parse", "HEAD").trim()

    @Test
    fun `a pull whose answer is lost pulls once and reports success`() =
        runComposeUiTest {
            val (default, remote) = projectBehindItsRemote()
            val app = launchWithProject(project, chaos = true)
            awaitTag(GitTags.branchBehind(default), unmerged = true)
            val target = gitIn(remote, "rev-parse", default).trim()

            // The pull is made, then the connection breaks before its answer: the retry must not pull a second time.
            app.network.dropFromServer = true
            onNodeWithTag(GitTags.QUICK_PULL).performClick()
            awaitCondition { head() == target }
            app.network.cutAll()
            app.network.dropFromServer = false

            awaitSyncStatus("Pulled $default")
            assertEquals(target, head())
            awaitNoTag(GitTags.branchBehind(default))
        }

    @Test
    fun `a pull clicked while the network is down waits for it and is made once it is back`() =
        runComposeUiTest {
            val (default, remote) = projectBehindItsRemote()
            val app = launchWithProject(project, chaos = true)
            awaitTag(GitTags.branchBehind(default), unmerged = true)
            val before = head()

            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(GitTags.QUICK_PULL).performClick()
            // Nothing pretends to be done while the link is down.
            awaitSyncStatus("Pulling $default")
            assertEquals(before, head())

            app.network.comeBack()
            awaitReconnected()
            awaitSyncStatus("Pulled $default")
            assertEquals(gitIn(remote, "rev-parse", default).trim(), head())
        }

    @Test
    fun `the Pull button works again after the server restarts`() =
        runComposeUiTest {
            val (default, remote) = projectBehindItsRemote()
            val app = launchWithProject(project, chaos = true)
            awaitTag(GitTags.branchBehind(default), unmerged = true)

            app.process.restart()
            awaitReconnected()
            awaitTag(GitTags.QUICK_PULL).assertIsEnabled()
            onNodeWithTag(GitTags.QUICK_PULL).performClick()

            awaitSyncStatus("Pulled $default")
            assertEquals(gitIn(remote, "rev-parse", default).trim(), head())
        }

    @Test
    fun `a slow link keeps the toolbar responsive during the pull`() =
        runComposeUiTest {
            val (default, _) = projectBehindItsRemote()
            val app = launchWithProject(project, chaos = true)
            awaitTag(GitTags.branchBehind(default), unmerged = true)

            app.network.latencyMs = SLOW_PULL_LINK_MS
            onNodeWithTag(GitTags.QUICK_PULL).performClick()
            // The click shows its progress at once, without waiting for the round trip.
            awaitSyncStatus("Pulling $default")
            onNodeWithTag(GitTags.GIT_BRANCH).performClick()
            awaitTag(GitTags.BRANCH_PICKER)
            awaitSyncStatus("Pulled $default")
        }
}

// Slow, but under the 1 s after which the chaos client treats a silent link as dead.
private const val SLOW_PULL_LINK_MS = 300L
