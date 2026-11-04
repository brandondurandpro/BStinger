package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test

private const val BRANCHES = 3_000
private const val REMOTE_COMMITS = 500

/**
 * Performance of the Pull button on a repository with [BRANCHES] local branches, all pinned, and a remote [REMOTE_COMMITS]
 * commits ahead: the pull, then the reload of every branch and chip it triggers, must not grow with the pins.
 */
@OptIn(ExperimentalTestApi::class)
class QuickPullPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    private fun createBranches() {
        val head = project.git("rev-parse", "HEAD").trim()
        repeat(BRANCHES) { index ->
            val name = "monitored/branch-${index.toString().padStart(4, '0')}"
            val ref = project.root.resolve(".git/refs/heads/$name")
            ref.parent.createDirectories()
            ref.writeText("$head\n")
        }
    }

    // A remote [REMOTE_COMMITS] commits ahead of the project's default branch, not fetched yet. `git fast-import`
    // writes them in one process: two processes per commit took about a minute on a Windows runner.
    private fun remoteAhead(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        project.git("push", "-q", "-u", "origin", "HEAD")
        val script = StringBuilder()
        repeat(REMOTE_COMMITS) { index ->
            val message = "remote $index"
            val content = "$index\n"
            script.append("commit refs/heads/$default\n")
            script.append("committer Test <test@example.com> ${1_700_000_000 + index} +0000\n")
            script.append("data ${message.length}\n$message\n")
            if (index == 0) script.append("from $default^0\n")
            script.append("M 644 inline remote-$index.txt\n")
            script.append("data ${content.length}\n$content\n")
        }
        gitFastImport(remote, script.toString())
        return default
    }

    @Test
    fun `pulling many commits with thousands of pinned branches stays fast`() {
        // Built before runComposeUiTest, whose one-minute timeout is for the app, not for the repository.
        val default = remoteAhead()
        createBranches()
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitCondition {
                app.project.git.branchInfos.size > BRANCHES
            }
            awaitTag(GitTags.branchBehind(default), unmerged = true)
            app.project.git.pickBranches(app.project.git.branches)
            app.project.git.pinPickedBranches()
            app.project.git.clearBranchPicks()
            renderFrame()

            val label = "quick pull of $REMOTE_COMMITS commits, $BRANCHES pins"
            measure(label, budgetMillis = PULL_BUDGET_MILLIS, budgetMegabytes = PULL_BUDGET_MEGABYTES) {
                onNodeWithTag(GitTags.QUICK_PULL).performClick()
                awaitCondition {
                    val status = onNodeWithTag(GitTags.BRANCH_SYNC_STATUS)
                    runCatching { status.assertTextContains("Pulled $default", substring = true) }.isSuccess
                }
                awaitNoTag(GitTags.branchBehind(default))
                renderFrame()
            }
            // The chips are virtualized: the pull reloads thousands of pins but composes only the visible ones.
            val composed = onAllNodes(hasTestTagPrefix("branch.pinned:")).fetchSemanticsNodes().size
            check(composed < MAX_COMPOSED_CHIPS) { "$composed pinned chips composed" }
        }
    }
}

private const val MAX_COMPOSED_CHIPS = 100

// About 30 times what a laptop measures (110 ms, 5 MB): a slow runner passes, a rebuild per pin does not.
private const val PULL_BUDGET_MILLIS = 3_000L
private const val PULL_BUDGET_MEGABYTES = 300L
