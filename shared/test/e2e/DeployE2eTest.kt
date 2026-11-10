package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deploying files and directories of the project to a folder, here or on another machine, from the project tree. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeployE2eTest {
    private val project = SampleProject.standard()
    private val scratch = Files.createTempDirectory("kotlin-editor-deploy")
    private val target = scratch.resolve("site")

    @AfterTest
    fun cleanup() {
        project.delete()
        scratch.deleteRecursively()
    }

    @Test
    fun `a directory is deployed to a folder and the dialog says what was copied`() =
        runComposeUiTest {
            launchWithProject(project)
            val result = deployFromTree("docs", target.toString())
            assertContains(result, "Deployed 1 file")
            assertContains(result, target.toString())
            assertEquals("Guide\nHello from the guide\n", target.resolve("docs/guide.md").readText())
            // Only what was chosen, and nothing of the project changed.
            assertTrue(!target.resolve("README.md").exists())
            assertEquals("Guide\nHello from the guide\n", project.read("docs/guide.md"))
        }

    @Test
    fun `a file is deployed with its place in the project and Git data is left out`() =
        runComposeUiTest {
            launchWithProject(project)
            deployFromTree("src", target.toString())
            assertTrue(target.resolve("src/main/kotlin/App.kt").exists())
            assertTrue(target.resolve("src/test/kotlin/AppTest.kt").exists())
            onNodeWithText("Close").performClick()
            awaitNoTag(AppTags.FILE_DIALOG)
            val whole = scratch.resolve("whole")
            deployFromTree("README.md", whole.toString())
            assertEquals(project.read("README.md"), whole.resolve("README.md").readText())
            assertTrue(!whole.resolve(".git").exists())
        }

    @Test
    fun `the destination of the last deployment is offered again`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            deployFromTree("docs", target.toString())
            onNodeWithText("Close").performClick()
            awaitNoTag(AppTags.FILE_DIALOG)
            assertEquals(target.toString(), app.project.deployDestination)
            openDeployDialog("src")
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).assertTextContains(target.toString(), substring = true)
        }

    @Test
    fun `an empty destination shows an error and copies nothing`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployDialog("docs")
            submitDeploy("   ")
            awaitTag(AppTags.FILE_DIALOG_ERROR).assertTextContains("Enter a folder", substring = true)
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_RESULT).fetchSemanticsNodes().isEmpty())
            assertTrue(!target.exists())
        }

    @Test
    fun `a destination inside what is deployed is refused and nothing is written`() =
        runComposeUiTest {
            launchWithProject(project)
            val inside = project.root.resolve("docs/out")
            openDeployDialog("docs")
            submitDeploy(inside.toString())
            awaitTag(AppTags.FILE_DIALOG_ERROR).assertTextContains("is inside", substring = true)
            assertTrue(!inside.exists())
            // The dialog stays open to correct the destination, and deploys once it is.
            submitDeploy(target.toString())
            assertContains(awaitDeployResult(), "Deployed 1 file")
        }

    @Test
    fun `cancelling closes the dialog and copies nothing`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployDialog("docs")
            onNodeWithText("Cancel").performClick()
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue(!target.exists())
        }

    @Test
    fun `a directory that another program deleted is reported, not left loading`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployDialog("docs")
            project.root.resolve("docs").deleteRecursively()
            submitDeploy(target.toString())
            awaitTag(AppTags.FILE_DIALOG_ERROR).assertTextContains("does not exist", substring = true)
            assertTrue(!target.exists())
        }

    @Test
    fun `a folder of another machine is reached over ssh with the host that was typed`() =
        runComposeUiTest {
            val home = scratch.resolve("home").createDirectories()
            val ssh = fakeSsh(scratch, home)
            if (ssh != null) {
                launchWithProject(project, ssh = ssh)
                val result = deployFromTree("docs", "web@example.com:site")
                assertContains(result, "Deployed 1 file")
                assertContains(result, "web@example.com:site")
                assertEquals("Guide\nHello from the guide\n", home.resolve("site/docs/guide.md").readText())
                assertEquals("web@example.com", scratch.resolve("host.txt").readText().trim())
            }
        }

    @Test
    fun `a refused ssh connection is reported with what ssh said`() =
        runComposeUiTest {
            val home = scratch.resolve("home").createDirectories()
            val ssh = fakeSsh(scratch, home, failure = "Permission denied (publickey).")
            if (ssh != null) {
                launchWithProject(project, ssh = ssh)
                openDeployDialog("docs")
                submitDeploy("staging:site")
                awaitTag(AppTags.FILE_DIALOG_ERROR).assertTextContains("Permission denied", substring = true)
                assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_RESULT).fetchSemanticsNodes().isEmpty())
                assertTrue(!home.resolve("site").exists())
                // Still usable: the tree answers.
                awaitTag(TreeTags.treeRow("docs"))
            }
        }
}
