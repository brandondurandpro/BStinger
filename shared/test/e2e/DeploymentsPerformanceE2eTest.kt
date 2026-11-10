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

// Windows runners write files several times slower (antivirus scan of each one): fewer files there.
private val FILES = if (WINDOWS_DISK) 500 else 2_000

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
            // The groups exist before the deployment: a file written at once in a directory the watch of the
            // server has not registered yet is not what this test measures.
            for (group in 0..(FILES - 1) / 100) project.dir("big/g$group")
            val app = launchWithProject(project)
            openDeployments()
            createDeployment("Big", "big", target.toString(), auto = true)
            closeDeployments()
            measure("deploy $FILES changed files automatically", budgetMillis = 60_000, budgetMegabytes = 800) {
                repeat(FILES) { project.file("big/g${it / 100}/f$it.txt", "content $it") }
                val last = target.resolve("big/g${(FILES - 1) / 100}/f${FILES - 1}.txt")
                try {
                    awaitCondition(timeout = 30_000) { last.exists() }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                    val copied = Files.walk(target).use { it.filter(Files::isRegularFile).count() }
                    error("$copied of $FILES files copied, status ${app.project.deployments.statuses} ${app.project.deployments.autoDebug}: ${e.message}")
                }
                renderFrame()
            }
            check(app.project.deployments.profiles.size == 1)
        }
}
