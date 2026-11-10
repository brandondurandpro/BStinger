package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test

private const val PROFILES = 300
private const val FILES = 2_000

/** Many saved deployments, and an automatic one hit by thousands of changed files at once (a build, a checkout). */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeploymentsPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val scratch = Files.createTempDirectory("kotlin-editor-deployments-perf")
    private val target = scratch.resolve("out")

    @AfterTest
    fun cleanup() {
        project.delete()
        scratch.deleteRecursively()
    }

    @Test
    fun `a list of three hundred deployments opens quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val many =
                (0 until PROFILES).map {
                    DeployProfile(
                        "Deployment $it",
                        listOf(DeployFolder("docs", "docs")),
                        "$target/$it",
                        auto = it % 2 == 0,
                    )
                }
            runBlocking {
                app.project.services.deployProfiles
                    .save(project.path, many)
            }
            measure("open $PROFILES deployments", budgetMillis = 5_000, budgetMegabytes = 300) {
                openDeployments()
                awaitTag(AppTags.deployment("Deployment 0"))
                renderFrame()
            }
        }

    @Test
    fun `two thousand changed files are deployed by an automatic deployment in a few batches`() =
        runComposeUiTest {
            project.dir("big")
            val app = launchWithProject(project)
            openDeployments()
            createDeployment("Big", "big", target.toString(), auto = true)
            closeDeployments()
            measure("deploy $FILES changed files automatically", budgetMillis = 20_000, budgetMegabytes = 400) {
                repeat(FILES) { project.file("big/g${it / 100}/f$it.txt", "content $it") }
                awaitCondition(
                    timeout = 20_000,
                ) { target.resolve("big/g${(FILES - 1) / 100}/f${FILES - 1}.txt").exists() }
                renderFrame()
            }
            check(app.project.deployments.profiles.size == 1)
        }
}
