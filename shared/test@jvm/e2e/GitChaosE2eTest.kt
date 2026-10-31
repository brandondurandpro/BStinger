package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Slow (a 600 ms round trip), but under the 1 s after which the chaos client treats a silent link as dead.
private const val SLOW_BRANCH_LINK_MS = 300L

/** Git, which runs on the server, while the network fails. */
@OptIn(ExperimentalTestApi::class)
class GitChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the Git panel opened while the network is down lists the changes once it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project, chaos = true)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            app.network.comeBack()
            awaitTag(ItemTags.gitChange("README.md"))
        }

    @Test
    fun `the branch picker keeps its branches while the network is down and refreshes when it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature" } }
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            // The last known branches stay usable; the refresh issued on opening waits for the link.
            awaitTag(ItemTags.branchItem("feature"))
            onNodeWithTag(Tags.BRANCH_REFRESH).performClick()
            app.network.comeBack()
            awaitReconnected()
            awaitCondition { app.project.gitBranchInfos.any { it.name == "feature" } }
            awaitTag(ItemTags.branchItem("feature"))
        }

    @Test
    fun `the branch picker survives a server restart and refreshes the branches after it`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.isNotEmpty() }
            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            project.git("branch", "after-restart")

            // The restarted server loses its sessions; the panel must stay usable and reach the new one.
            app.process.restart()
            awaitReconnected()
            awaitTag(Tags.BRANCH_PICKER)
            onNodeWithTag(Tags.BRANCH_REFRESH).performClick()
            awaitTag(ItemTags.branchItem("after-restart"))
        }

    @Test
    fun `a slow link still opens the picker and refreshes its branches`() =
        runComposeUiTest {
            project.commitAll()
            val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            val app = launchWithProject(project, chaos = true)
            awaitCondition { app.project.gitBranchInfos.any { it.name == default } }
            project.git("branch", "slow-link")
            app.network.latencyMs = SLOW_BRANCH_LINK_MS

            onNodeWithTag(Tags.GIT_BRANCH).performClick()
            awaitTag(Tags.BRANCH_PICKER)
            // The panel and its cached rows open without waiting for the slow round trip it triggers.
            awaitTag(ItemTags.branchItem(default), timeout = 2_000)

            // Once the refresh crosses the link, the new branch shows up under the current query.
            onNodeWithTag(Tags.BRANCH_SEARCH).performTextInput("slow-link")
            awaitTag(ItemTags.branchItem("slow-link"), timeout = 15_000)
        }

    @Test
    fun `a commit whose answer is lost is made once`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project, chaos = true)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            awaitTag(Tags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            // The commit is made, then the connection breaks before its answer arrives: the client sends it again.
            app.network.dropFromServer = true
            onNodeWithTag(Tags.GIT_COMMIT).performClick()
            awaitCondition { project.lastCommitSubject() == "Update the readme" }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitCondition { !app.project.gitBusy && app.project.gitStatus.isEmpty() }
            assertEquals("Committed", app.project.gitMessage)
            assertEquals(2, project.commitCount(), "the retried commit must not run twice")
        }
}
