package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLOW_LINK_MS = 400L

/** Deploying while the network fails: nothing is lost, nothing runs twice, nothing stays stuck. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeployChaosE2eTest {
    private val project = SampleProject.standard()
    private val scratch = Files.createTempDirectory("kotlin-editor-deploy-chaos")
    private val target = scratch.resolve("site")

    @AfterTest
    fun cleanup() {
        project.delete()
        scratch.deleteRecursively()
    }

    @Test
    fun `a deployment asked while the network is down runs once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployDialog("docs")
            app.network.goDown()
            awaitReconnecting()
            submitDeploy(target.toString())
            // Waiting, not failed: the dialog shows neither a result nor an error.
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_RESULT).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isEmpty())
            app.network.comeBack()
            assertContains(awaitDeployResult(), "Deployed 1 file")
            assertEquals("Guide\nHello from the guide\n", target.resolve("docs/guide.md").readText())
        }

    @Test
    fun `a deployment whose answer is lost runs once and is not reported as failed`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployDialog("docs")
            // The server deploys, then the connection breaks before the answer arrives: the client asks again.
            app.network.dropFromServer = true
            submitDeploy(target.toString())
            awaitCondition { target.resolve("docs/guide.md").toFile().exists() }
            // What someone did to the destination meanwhile: a second run would overwrite it.
            // On Windows the deployer may still hold the file it is copying: write once it lets go.
            val deployed = target.resolve("docs/guide.md")
            awaitCondition { runCatching { deployed.writeText("edited on the destination") }.isSuccess }
            app.network.cutAll()
            app.network.dropFromServer = false
            assertContains(awaitDeployResult(), "Deployed 1 file")
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isEmpty())
            assertEquals("edited on the destination", target.resolve("docs/guide.md").readText())
        }

    @Test
    fun `a slow link keeps the dialog usable and the late answer is shown`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployDialog("docs")
            app.network.latencyMs = SLOW_LINK_MS
            submitDeploy(target.toString())
            // Deploy is replaced by its running label; the dialog still answers to Cancel's presence.
            onNodeWithTag(AppTags.FILE_DIALOG).assertExists()
            assertContains(awaitDeployResult(), "Deployed 1 file")
            assertEquals("Guide\nHello from the guide\n", target.resolve("docs/guide.md").readText())
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).assertExists()
        }

    @Test
    fun `a server that restarts before answering is reported and a second try deploys`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployDialog("docs")
            app.network.dropFromServer = true
            submitDeploy(target.toString())
            awaitCondition { target.resolve("docs/guide.md").toFile().exists() }
            app.process.restart()
            app.network.dropFromServer = false
            app.network.cutAll()
            // Whatever the dialog says, it is not stuck on Deploying…: it ends with a result or an error.
            awaitCondition {
                onAllNodesWithTag(AppTags.FILE_DIALOG_RESULT).fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isNotEmpty()
            }
            awaitReconnected()
            if (onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isNotEmpty()) {
                onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
                assertContains(awaitDeployResult(), "Deployed 1 file")
            }
            assertEquals("Guide\nHello from the guide\n", target.resolve("docs/guide.md").readText())
        }
}
