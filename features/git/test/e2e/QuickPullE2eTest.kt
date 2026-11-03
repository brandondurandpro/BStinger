package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Pull button of the toolbar: the project fetches its remotes when it opens, the current branch shows the commits
 * to pull, and one click pulls them (fast-forward only).
 */
@OptIn(ExperimentalTestApi::class)
class QuickPullE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extras.add(it) }

    // The project tracks a bare remote. Returns the default branch and the remote.
    private fun projectWithRemote(): Pair<String, Path> {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = temporaryDirectory("kotlin-editor-remote")
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        return default to remote
    }

    // Adds a commit to [branch] on the remote through a clone, without fetching it: the IDE has to.
    private fun advanceRemote(remote: Path, branch: String, file: String = "remote.txt") {
        val clone = temporaryDirectory("kotlin-editor-clone")
        gitIn(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
        gitIn(clone, "checkout", "-q", branch)
        clone.resolve(file).writeText("from the remote\n")
        gitIn(clone, "add", "-A")
        gitIn(clone, "commit", "-q", "-m", "remote work")
        gitIn(clone, "push", "-q")
    }

    private fun ComposeUiTest.awaitSyncStatus(text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(GitTags.BRANCH_SYNC_STATUS).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }

    @Test
    fun `the project fetches when it opens and the Pull button brings the remote commits in one click`() =
        runComposeUiTest {
            val (default, remote) = projectWithRemote()
            advanceRemote(remote, default)
            launchWithProject(project)
            // Nobody fetched in the test: the count comes from the fetch the project makes when it opens.
            awaitTag(GitTags.branchBehind(default), unmerged = true)

            onNodeWithTag(GitTags.QUICK_PULL).performClick()

            awaitSyncStatus("Pulled $default")
            assertEquals(gitIn(remote, "rev-parse", default).trim(), project.git("rev-parse", "HEAD").trim())
            assertEquals("from the remote\n", project.root.resolve("remote.txt").readText())
            awaitNoTag(GitTags.branchBehind(default))
        }

    @Test
    fun `the Pull button refuses to merge a current branch that diverged from its remote`() =
        runComposeUiTest {
            val (default, remote) = projectWithRemote()
            advanceRemote(remote, default)
            project.git("commit", "-q", "--allow-empty", "-m", "local work")
            val before = project.git("rev-parse", "HEAD").trim()
            val app = launchWithProject(project)
            awaitTag(GitTags.branchBehind(default), unmerged = true)

            onNodeWithTag(GitTags.QUICK_PULL).performClick()

            awaitSyncStatus("Cannot pull $default")
            assertEquals(before, project.git("rev-parse", "HEAD").trim())
            assertFalse(project.root.resolve("remote.txt").exists(), "nothing may be merged")
            assertFalse(app.project.git.busy)
        }

    @Test
    fun `the Pull button is disabled when the current branch tracks no remote branch`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project)
            awaitCondition {
                app.project.git.branchInfos
                    .any { it.current }
            }
            awaitTag(GitTags.QUICK_PULL).assertIsNotEnabled()
        }

    @Test
    fun `an up to date branch is pulled without error and nothing changes`() =
        runComposeUiTest {
            val (default, _) = projectWithRemote()
            val before = project.git("rev-parse", "HEAD").trim()
            launchWithProject(project)
            awaitTag(GitTags.QUICK_PULL)

            onNodeWithTag(GitTags.QUICK_PULL).performClick()

            awaitSyncStatus("Pulled $default")
            assertEquals(before, project.git("rev-parse", "HEAD").trim())
        }

    @Test
    fun `pinned branches are saved with the project and come back when it is opened again`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            project.git("branch", "deleted-meanwhile")
            val key = "git.pinnedBranches.${project.path}"
            // Saved by an earlier session; `deleted-meanwhile` was then deleted outside the IDE.
            project.git("branch", "-D", "deleted-meanwhile")
            val preferences = InMemoryPreferenceStore(mapOf(key to "feature\ndeleted-meanwhile"))
            val app = launchWithProject(project, preferences)

            awaitTag(GitTags.pinnedBranch("feature"))
            awaitCondition { preferences.load()[key] == "feature" }
            assertEquals(setOf("feature"), app.project.git.selectedBranches)

            // Unpinning is saved too.
            onNodeWithTag(GitTags.unpinnedBranch("feature")).performClick()
            awaitNoTag(GitTags.pinnedBranch("feature"))
            awaitCondition { preferences.load()[key] == "" }
            assertTrue(
                app.project.git.selectedBranches
                    .isEmpty(),
            )
        }
}
