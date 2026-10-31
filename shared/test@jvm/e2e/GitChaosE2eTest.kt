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
