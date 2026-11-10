package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SLOW_LINK_MS = 300L
private const val REWRITE_NANOS = 1_500_000_000L

/** The saved deployments while the network fails or the server restarts: nothing lost, nothing saved twice. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeploymentsChaosE2eTest {
    private val project = SampleProject.standard()
    private val scratch = Files.createTempDirectory("kotlin-editor-deployments-chaos")
    private val docs = scratch.resolve("docs-copy")

    @AfterTest
    fun cleanup() {
        project.delete()
        scratch.deleteRecursively()
    }

    @Test
    fun `a deployment saved while the network is down is saved once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            app.network.goDown()
            awaitReconnecting()
            fillDeployment("Docs", "docs", docs.toString())
            // Not listed yet, and no error: the save waits for the link.
            assertEquals(0, onAllNodesWithTag(AppTags.deployment("Docs")).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(AppTags.DEPLOYMENT_DRAFT_ERROR).fetchSemanticsNodes().size)
            app.network.comeBack()
            awaitTag(AppTags.deployment("Docs"))
            assertEquals(
                listOf("Docs"),
                runBlocking {
                    app.project.services.deployProfiles
                        .load(project.path)
                }.map { it.name },
            )
        }

    @Test
    fun `a deployment whose save answer is lost is listed and saved once`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            app.network.dropFromServer = true
            fillDeployment("Docs", "docs", docs.toString())
            awaitCondition {
                runBlocking {
                    app.project.services.deployProfiles
                        .load(project.path)
                }.isNotEmpty()
            }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitTag(AppTags.deployment("Docs"))
            assertEquals(1, onAllNodesWithTag(AppTags.deployment("Docs")).fetchSemanticsNodes().size)
            assertEquals(
                1,
                runBlocking {
                    app.project.services.deployProfiles
                        .load(project.path)
                }.size,
            )
        }

    @Test
    fun `the popup opened while the network is down lists the deployments once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployments()
            createDeployment("Docs", "docs", docs.toString())
            closeDeployments()
            app.network.goDown()
            awaitReconnecting()
            app.project.deployments.show()
            awaitTag(AppTags.DEPLOYMENTS)
            app.network.comeBack()
            awaitTag(AppTags.deployment("Docs"))
        }

    @Test
    fun `an automatic deployment works on a slow link and after the server restarted`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openDeployments()
            createDeployment("Docs", "docs", docs.toString(), auto = true)
            closeDeployments()
            app.network.latencyMs = SLOW_LINK_MS
            project.root.resolve("docs/guide.md").writeText("slow")
            awaitCondition { hasText(docs.resolve("docs/guide.md"), "slow") }
            app.network.latencyMs = 0
            app.process.restart()
            awaitReconnected()
            // The watch of the restarted server starts again: write again, now and then, until one change is seen.
            var round = 0
            var lastWrite = 0L
            awaitCondition {
                val now = System.nanoTime()
                if (now - lastWrite > REWRITE_NANOS) {
                    project.root.resolve("docs/guide.md").writeText("after restart ${round++}")
                    lastWrite = now
                }
                val deployed = docs.resolve("docs/guide.md")
                hasStartingText(deployed, "after restart")
            }
            onNodeWithTag(AppTags.DEPLOY_BUTTON).assertExists()
        }
}
