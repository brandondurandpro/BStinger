package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

private const val FILES = 5_000
private const val LINES_PER_FILE = 20

/** Deploying a directory of thousands of files through the real UI and server. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeployPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val scratch = Files.createTempDirectory("kotlin-editor-deploy-perf")
    private val target = scratch.resolve("site")

    @AfterTest
    fun cleanup() {
        project.delete()
        scratch.deleteRecursively()
    }

    private fun name(i: Int) = "huge/group${i / 100}/file${i.toString().padStart(5, '0')}.txt"

    @Test
    fun `a directory of five thousand files is deployed in seconds and the UI stays idle afterwards`() =
        runComposeUiTest {
            val text = "line of text\n".repeat(LINES_PER_FILE)
            repeat(FILES) { project.file(name(it), text) }
            launchWithProject(project)
            openDeployDialog("huge")
            var result = ""
            measure("deploy a directory of $FILES files", budgetMillis = 30_000, budgetMegabytes = 600) {
                submitDeploy(target.toString())
                result = awaitDeployResult()
                renderFrame()
            }
            assertContains(result, "Deployed $FILES files")
            assertTrue(target.resolve(name(0)).exists())
            assertTrue(target.resolve(name(FILES - 1)).exists())
            // Deploying again replaces every file: as fast as the first time.
            measure("deploy it again over the same files", budgetMillis = 30_000, budgetMegabytes = 600) {
                onNodeWithText("Close").performClick()
                awaitNoTag(AppTags.FILE_DIALOG)
                openDeployDialog("huge")
                submitDeploy(target.toString())
                awaitDeployResult()
                renderFrame()
            }
        }
}
