package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SSH_CONFIG = "kotlin.editor.ssh.config"
private const val GUIDE = "Guide\nHello from the guide\n"

/** The deployments saved for a project: which folders go where, run by a button or as soon as the files change. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeploymentsE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }
    private val scratch = Files.createTempDirectory("kotlin-editor-deployments")
    private val site = scratch.resolve("site")
    private val docs = scratch.resolve("docs-copy")

    @AfterTest
    fun cleanup() {
        System.clearProperty(SSH_CONFIG)
        project.delete()
        scratch.deleteRecursively()
    }

    private fun ComposeUiTest.listed(name: String) =
        onAllNodesWithTag(AppTags.deployment(name)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a deployment is saved with its folders and runs when the button is pressed`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "src, docs", site.toString())
            awaitTag(AppTags.deploymentRun("Site")).performClick()
            awaitCondition { awaitDeploymentStatus("Site").startsWith("Deployed 4 files") }
            assertEquals(GUIDE, site.resolve("docs/guide.md").readText())
            assertTrue(site.resolve("src/main/kotlin/App.kt").exists())
            assertTrue(!site.resolve("README.md").exists())
            before.assertUnchanged(project)
        }

    @Test
    fun `an empty list of folders deploys the whole project but its Git data`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Everything", "", site.toString())
            awaitTag(AppTags.deploymentRun("Everything")).performClick()
            awaitCondition { awaitDeploymentStatus("Everything").startsWith("Deployed") }
            assertTrue(site.resolve("README.md").exists())
            assertTrue(!site.resolve(".git").exists())
        }

    @Test
    fun `saved deployments are still there after the popup is closed and the server restarted`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openDeployments()
            createDeployment("Site", "docs", site.toString(), auto = true)
            closeDeployments()
            app.process.restart()
            awaitReconnected()
            openDeployments()
            awaitTag(AppTags.deployment("Site"))
            val saved =
                app.project.deployments.profiles
                    .firstOrNull { it.name == "Site" }
            assertEquals(true, saved?.auto)
            assertEquals(listOf("docs"), saved?.sources)
        }

    @Test
    fun `a deployment with no name, a bad destination, a folder outside the project or a name taken is refused`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "docs", site.toString())
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            fillDeployment("", "docs", site.toString())
            awaitTag(AppTags.DEPLOYMENT_DRAFT_ERROR).assertTextContains("name", substring = true)
            fillDeployment("Other", "docs", "")
            awaitTag(AppTags.DEPLOYMENT_DRAFT_ERROR).assertTextContains("Enter a folder", substring = true)
            fillDeployment("Other", "../elsewhere", site.toString())
            awaitTag(AppTags.DEPLOYMENT_DRAFT_ERROR).assertTextContains("inside the project", substring = true)
            fillDeployment("Site", "docs", site.toString())
            awaitTag(AppTags.DEPLOYMENT_DRAFT_ERROR).assertTextContains("exists already", substring = true)
            // Cancelling keeps what was saved, and nothing else.
            onNodeWithTag(AppTags.DEPLOYMENT_CANCEL).performClick()
            awaitTag(AppTags.deployment("Site"))
            assertTrue(!listed("Other"))
        }

    @Test
    fun `the destination is asked first and each folder then shows where it lands`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            // No folder field before the destination: only the reminder to give it.
            awaitTag(AppTags.DEPLOYMENT_FOLDERS_HINT)
            assertTrue(onAllNodesWithTag(AppTags.deploymentSource(0)).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("web@example.com:/var/www")
            awaitNoTag(AppTags.DEPLOYMENT_FOLDERS_HINT)
            onNodeWithTag(AppTags.deploymentSource(0)).performTextReplacement("docs")
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("web@example.com:/var/www/docs", substring = true)
            // A second folder has its own field and its own landing; the destination changes both.
            onNodeWithTag(AppTags.DEPLOYMENT_ADD_FOLDER).performClick()
            onNodeWithTag(AppTags.deploymentSource(1)).performTextReplacement("src/main")
            awaitTag(
                AppTags.deploymentLanding(1),
            ).assertTextContains("web@example.com:/var/www/src/main", substring = true)
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("/srv/app")
            awaitTag(AppTags.deploymentLanding(1)).assertTextContains("/srv/app/src/main", substring = true)
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/docs", substring = true)
            // Removing the first folder leaves the second in its place.
            onNodeWithTag(AppTags.deploymentRemoveFolder(0)).performClick()
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/src/main", substring = true)
            assertTrue(onAllNodesWithTag(AppTags.deploymentSource(1)).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `a folder outside the project is flagged where it is typed`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement(site.toString())
            onNodeWithTag(AppTags.deploymentSource(0)).performTextReplacement("../secrets")
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("Not a path inside the project", substring = true)
        }

    @Test
    fun `several folders are saved and deployed to their own place in the destination`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openDeployments()
            createDeployment("Both", "docs, src/main", site.toString())
            assertEquals(
                listOf("docs", "src/main"),
                app.project.deployments.profiles
                    .firstOrNull()
                    ?.sources,
            )
            awaitTag(AppTags.deploymentRun("Both")).performClick()
            awaitCondition { awaitDeploymentStatus("Both").startsWith("Deployed") }
            assertTrue(site.resolve("docs/guide.md").exists())
            assertTrue(site.resolve("src/main/kotlin/App.kt").exists())
            assertTrue(!site.resolve("src/test").exists())
        }

    @Test
    fun `the destination path of a folder follows its folder until it is changed, then stays`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("/srv/app")
            onNodeWithTag(AppTags.deploymentSource(0)).performTextReplacement("src/main")
            awaitTag(AppTags.deploymentTarget(0)).assertTextContains("src/main", substring = true)
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/src/main", substring = true)
            assertTrue(onAllNodesWithTag(AppTags.deploymentTargetReset(0)).fetchSemanticsNodes().isEmpty())
            // Another path on the destination: the preview follows it, and the folder no longer drags it along.
            onNodeWithTag(AppTags.deploymentTarget(0)).performTextReplacement("app/code")
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/app/code", substring = true)
            onNodeWithTag(AppTags.deploymentSource(0)).performTextReplacement("src/test")
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/app/code", substring = true)
            // Back to the same path as the folder.
            onNodeWithTag(AppTags.deploymentTargetReset(0)).performClick()
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("/srv/app/src/test", substring = true)
            assertTrue(onAllNodesWithTag(AppTags.deploymentTargetReset(0)).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `a folder is deployed at the path chosen for it, and the choice is kept when the deployment is edited`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openDeployments()
            createDeployment("Layout", "src/main=app/code, docs=, README.md=info/read-me.md", site.toString())
            val saved =
                app.project.deployments.profiles
                    .firstOrNull()
                    ?.folders
                    .orEmpty()
            assertEquals(
                listOf(
                    DeployFolder("src/main", "app/code"),
                    DeployFolder("docs", ""),
                    DeployFolder("README.md", "info/read-me.md"),
                ),
                saved,
            )
            awaitTag(AppTags.deploymentRun("Layout")).performClick()
            awaitCondition { awaitDeploymentStatus("Layout").startsWith("Deployed") }
            assertTrue(site.resolve("app/code/kotlin/App.kt").exists())
            assertEquals(GUIDE, site.resolve("guide.md").readText())
            assertEquals(project.read("README.md"), site.resolve("info/read-me.md").readText())
            assertTrue(!site.resolve("src").exists() && !site.resolve("docs").exists())
            // Editing shows the choices again, the folders that landed at their own path still following.
            openDeploymentMenu("Layout")
            awaitTag(AppTags.deploymentEdit("Layout")).performClick()
            awaitTag(AppTags.deploymentTarget(0)).assertTextContains("app/code", substring = true)
            awaitTag(AppTags.deploymentTargetReset(0))
            awaitTag(AppTags.deploymentTargetReset(1))
        }

    @Test
    fun `a destination path that leaves the destination is flagged and refused`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            fillDeployment("Escape", "docs=../elsewhere", site.toString())
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("Not a path", substring = true)
            awaitTag(
                AppTags.DEPLOYMENT_DRAFT_ERROR,
            ).assertTextContains("must stay inside the destination", substring = true)
            assertTrue(!site.exists())
        }

    @Test
    fun `a folder of an automatic deployment lands at its own path when a file of it changes`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Mapped", "docs=public/help", site.toString(), auto = true)
            closeDeployments()
            project.root.resolve("docs/guide.md").writeText("edited guide")
            project.file("docs/deep/new.md", "new")
            awaitCondition {
                hasText(site.resolve("public/help/guide.md"), "edited guide") &&
                    hasText(site.resolve("public/help/deep/new.md"), "new")
            }
            assertTrue(!site.resolve("docs").exists())
        }

    @Test
    fun `the form and the list say whether a destination is local or on another machine`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            // Nothing to say about a destination that is not typed yet.
            assertTrue(onAllNodesWithTag(AppTags.DEPLOYMENT_KIND).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement(site.toString())
            awaitTag(AppTags.DEPLOYMENT_KIND).assertTextContains("Local")
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("web@example.com:/var/www")
            awaitTag(AppTags.DEPLOYMENT_KIND).assertTextContains("Remote · web@example.com")
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("staging:site")
            awaitTag(AppTags.DEPLOYMENT_KIND).assertTextContains("Remote · staging")
            onNodeWithTag(AppTags.DEPLOYMENT_CANCEL).performClick()
            createDeployment("Here", "docs", site.toString())
            createDeployment("There", "docs", "web@example.com:/var/www")
            // Only the one reached over SSH says so; the line under the table counts them.
            awaitTag(AppTags.deploymentKind("There")).assertTextContains("SSH")
            assertTrue(onAllNodesWithTag(AppTags.deploymentKind("Here")).fetchSemanticsNodes().isEmpty())
            awaitTag(AppTags.DEPLOYMENTS_SUMMARY).assertTextContains("1 local, 1 over SSH", substring = true)
        }

    @Test
    fun `an SSH destination is picked among the servers of the SSH configuration`() =
        runComposeUiTest {
            val config = scratch.resolve("ssh-config")
            config.writeText(
                """
                Host staging
                  HostName 192.0.2.7
                  User deploy
                Host prod
                  HostName 192.0.2.8
                """.trimIndent(),
            )
            System.setProperty(SSH_CONFIG, config.toString())
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            // A folder of the disk to begin with: no server to pick.
            assertTrue(onAllNodesWithTag(AppTags.DEPLOYMENT_SSH_HOST).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(AppTags.DEPLOYMENT_KIND_SSH).performClick()
            // The servers are in a menu, closed until asked for.
            assertTrue(onAllNodesWithTag(AppTags.deploymentSshHost("prod")).fetchSemanticsNodes().isEmpty())
            awaitTag(AppTags.DEPLOYMENT_SSH_PICKER).performClick()
            awaitTag(AppTags.deploymentSshHost("prod"))
            // The search narrows the list, an unknown name leaves nothing.
            onNodeWithTag(AppTags.DEPLOYMENT_SSH_SEARCH).performTextReplacement("192.0.2.7")
            awaitNoTag(AppTags.deploymentSshHost("prod"))
            onNodeWithTag(AppTags.DEPLOYMENT_SSH_SEARCH).performTextReplacement("nothing like this")
            awaitTag(AppTags.DEPLOYMENT_SSH_NONE)
            onNodeWithTag(AppTags.DEPLOYMENT_SSH_SEARCH).performTextReplacement("")
            awaitTag(AppTags.deploymentSshHost("prod"))
            awaitTag(AppTags.deploymentSshHost("staging")).assertTextContains("deploy@192.0.2.7", substring = true)
            // The folder on the server is asked once a server is chosen.
            assertTrue(onAllNodesWithTag(AppTags.DEPLOYMENT_DESTINATION).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(AppTags.deploymentSshHost("staging")).performClick()
            // Picking a server closes the menu.
            awaitNoTag(AppTags.DEPLOYMENT_SSH_SEARCH)
            awaitTag(AppTags.DEPLOYMENT_KIND).assertTextContains("Remote · staging")
            onNodeWithTag(AppTags.DEPLOYMENT_NAME).performTextReplacement("Staging")
            awaitTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("/var/www")
            awaitTag(AppTags.deploymentSource(0)).performTextReplacement("docs")
            onNodeWithTag(AppTags.DEPLOYMENT_SAVE).performClick()
            awaitTag(AppTags.deploymentKind("Staging")).assertTextContains("SSH")
            assertTrue(onAllNodesWithText("staging:/var/www").fetchSemanticsNodes().isNotEmpty())
            // Editing it opens the SSH choice again, with the server selected and its folder.
            openDeploymentMenu("Staging")
            awaitTag(AppTags.deploymentEdit("Staging")).performClick()
            awaitTag(AppTags.DEPLOYMENT_SSH_HOST)
            awaitTag(AppTags.DEPLOYMENT_DESTINATION)
            // Back to a folder of the disk: the SSH destination is dropped.
            onNodeWithTag(AppTags.DEPLOYMENT_KIND_LOCAL).performClick()
            awaitNoTag(AppTags.DEPLOYMENT_SSH_HOST)
            assertTrue(onAllNodesWithTag(AppTags.DEPLOYMENT_KIND).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `an SSH destination can be typed when the configuration lists no server`() =
        runComposeUiTest {
            val config = scratch.resolve("empty-ssh-config")
            config.writeText("")
            System.setProperty(SSH_CONFIG, config.toString())
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            onNodeWithTag(AppTags.DEPLOYMENT_KIND_SSH).performClick()
            awaitTag(AppTags.DEPLOYMENT_SSH_HOST).performTextReplacement("web@example.com")
            awaitTag(AppTags.DEPLOYMENT_KIND).assertTextContains("Remote · web@example.com")
        }

    @Test
    fun `a deployment is duplicated into a new one that is saved under its own name`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "docs=guide, src", site.toString())
            openDeploymentMenu("Site")
            awaitTag(AppTags.deploymentDuplicate("Site")).performClick()
            // The form opens filled like the original, named after it; the original is untouched meanwhile.
            awaitTag(AppTags.DEPLOYMENT_NAME).assertTextContains("Site copy")
            awaitTag(AppTags.deploymentSource(1)).assertTextContains("src")
            awaitTag(AppTags.deploymentTarget(0)).assertTextContains("guide")
            onNodeWithTag(AppTags.DEPLOYMENT_SAVE).performClick()
            awaitTag(AppTags.deployment("Site copy"))
            assertTrue(listed("Site"))
            // A second copy takes the next free name.
            openDeploymentMenu("Site")
            awaitTag(AppTags.deploymentDuplicate("Site")).performClick()
            awaitTag(AppTags.DEPLOYMENT_NAME).assertTextContains("Site copy 2")
        }

    @Test
    fun `a click on an empty place of the popup leaves it open`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "docs", site.toString())
            // The bottom right corner of the panel holds nothing: a click there is still a click inside it.
            onNodeWithTag(AppTags.DEPLOYMENTS).performMouseInput { click(Offset(width - 4f, height - 4f)) }
            waitForIdle()
            awaitTag(AppTags.deployment("Site"))
            onNodeWithTag(AppTags.DEPLOYMENTS).performMouseInput { click(Offset(width / 2f, 4f)) }
            waitForIdle()
            awaitTag(AppTags.deployment("Site"))
            // The cross closes it.
            onNodeWithTag(AppTags.DEPLOYMENTS_CLOSE).performClick()
            awaitNoTag(AppTags.DEPLOYMENTS)
        }

    @Test
    fun `a click beside the popup closes the list but not a form being filled`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            onNodeWithTag(AppTags.DEPLOYMENT_NAME).performTextReplacement("Kept")
            onRoot().performMouseInput { click(Offset(20f, 300f)) }
            waitForIdle()
            onNodeWithTag(AppTags.DEPLOYMENT_NAME).assertTextContains("Kept")
            onNodeWithTag(AppTags.DEPLOYMENT_CANCEL).performClick()
            awaitTag(AppTags.DEPLOYMENTS_NEW)
            onRoot().performMouseInput { click(Offset(20f, 300f)) }
            awaitNoTag(AppTags.DEPLOYMENTS)
        }

    @Test
    fun `the folders are a table whose columns line up, whatever the buttons in them`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement("/srv/app")
            onNodeWithTag(AppTags.deploymentSource(0)).performTextReplacement("src/main")
            onNodeWithTag(AppTags.DEPLOYMENT_ADD_FOLDER).performClick()
            awaitTag(AppTags.deploymentSource(1)).performTextReplacement("docs")

            fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            // Each column starts and ends at the same place on every row, the destination right of the project.
            assertEquals(bounds(AppTags.deploymentSource(0)).left, bounds(AppTags.deploymentSource(1)).left)
            assertEquals(bounds(AppTags.deploymentTarget(0)).left, bounds(AppTags.deploymentTarget(1)).left)
            assertEquals(bounds(AppTags.deploymentTarget(0)).right, bounds(AppTags.deploymentTarget(1)).right)
            assertTrue(bounds(AppTags.deploymentSource(0)).right < bounds(AppTags.deploymentTarget(0)).left)
            // Another path: the button that brings back the same path is inside the field, which keeps its place.
            val before = bounds(AppTags.deploymentTarget(0))
            onNodeWithTag(AppTags.deploymentTarget(0)).performTextReplacement("app/code")
            awaitTag(AppTags.deploymentTargetReset(0))
            assertEquals(before, bounds(AppTags.deploymentTarget(0)))
            assertEquals(bounds(AppTags.deploymentTarget(1)).right, bounds(AppTags.deploymentTarget(0)).right)
        }

    @Test
    fun `the list is a table of the deployments, with where they go said once`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "src/main, docs", site.toString())
            createDeployment("Docs", "docs", docs.toString(), auto = true)
            // One file per line, in the order of the deployment.
            val first = awaitTag(AppTags.deploymentFile("Site", 0)).assertTextContains("main")
            val second = awaitTag(AppTags.deploymentFile("Site", 1)).assertTextContains("docs")
            val firstBounds = first.fetchSemanticsNode().boundsInRoot
            val secondBounds = second.fetchSemanticsNode().boundsInRoot
            assertTrue(secondBounds.top >= firstBounds.bottom)
            assertEquals(firstBounds.left, secondBounds.left)
            awaitTag(AppTags.DEPLOYMENTS_SUMMARY).assertTextContains("All targets are Local.", substring = true)
            // The trigger of each, and the other actions behind the menu of its row.
            assertTrue(onAllNodesWithText("Automatic").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithTag(AppTags.deploymentEdit("Site")).fetchSemanticsNodes().isEmpty())
            openDeploymentMenu("Site")
            awaitTag(AppTags.deploymentEdit("Site"))
            awaitTag(AppTags.deploymentDuplicate("Site"))
            awaitTag(AppTags.deploymentDelete("Site"))
        }

    @Test
    fun `a deployment can be edited and deleted`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Site", "docs", site.toString())
            openDeploymentMenu("Site")
            awaitTag(AppTags.deploymentEdit("Site")).performClick()
            fillDeployment("Docs", "docs", docs.toString())
            awaitTag(AppTags.deployment("Docs"))
            assertTrue(!listed("Site"))
            awaitTag(AppTags.deploymentRun("Docs")).performClick()
            awaitCondition { docs.resolve("docs/guide.md").exists() }
            openDeploymentMenu("Docs")
            awaitTag(AppTags.deploymentDelete("Docs")).performClick()
            awaitNoTag(AppTags.deployment("Docs"))
            awaitTag(AppTags.DEPLOYMENTS_EMPTY)
        }

    @Test
    fun `the entry selected in the project view is added to the folders of the deployment`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(TreeTags.treeRow("docs")).performClick()
            openDeployments()
            awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
            // The destination first; the selected entry is then the first folder.
            onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement(site.toString())
            awaitTag(AppTags.deploymentSource(0)).assertTextContains("docs", substring = true)
            awaitTag(AppTags.deploymentLanding(0)).assertTextContains("site/docs", substring = true)
        }

    @Test
    fun `a deployment that just ran in the Deploy dialog can be saved`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            deployFromTree("docs", site.toString())
            awaitTag(AppTags.DEPLOY_REMEMBER).performClick()
            awaitCondition {
                app.project.deployments.profiles
                    .isNotEmpty()
            }
            onNodeWithText("Close").performClick()
            openDeployments()
            awaitTag(AppTags.deployment("docs"))
            val saved =
                app.project.deployments.profiles
                    .firstOrNull { it.name == "docs" }
            assertEquals(site.toString(), saved?.destination)
            assertEquals(listOf("docs"), saved?.sources)
        }

    @Test
    fun `an automatic deployment sends the files of its folders as soon as they change, and only them`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Docs", "docs", docs.toString(), auto = true)
            createDeployment("Manual", "src", site.toString())
            closeDeployments()
            // A file of a manual deployment and one outside every deployment change first.
            project.root.resolve("src/main/kotlin/App.kt").writeText("// changed")
            project.root.resolve("README.md").writeText("changed")
            project.root.resolve("docs/guide.md").writeText("edited guide")
            awaitCondition { hasText(docs.resolve("docs/guide.md"), "edited guide") }
            // Only what changed was sent: the other files of the folder were not.
            assertTrue(!docs.resolve("README.md").exists())
            assertTrue(!site.exists())
            openDeployments()
            assertContains(awaitDeploymentStatus("Docs"), "Automatic: Deployed 1 file")
        }

    @Test
    fun `a new file of an automatic deployment is sent, a deleted one is not`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Docs", "docs", docs.toString(), auto = true)
            closeDeployments()
            project.file("docs/new/notes.md", "notes")
            awaitCondition { docs.resolve("docs/new/notes.md").exists() }
            docs.resolve("docs/new").createDirectories()
            project.root
                .resolve("docs/new/notes.md")
                .toFile()
                .delete()
            project.file("docs/after.md", "after")
            awaitCondition { docs.resolve("docs/after.md").exists() }
            // Nothing is deleted on the destination, and the deletion was no error.
            assertTrue(docs.resolve("docs/new/notes.md").exists())
            openDeployments()
            assertContains(awaitDeploymentStatus("Docs"), "Automatic: Deployed")
        }

    @Test
    fun `an automatic deployment into the project does not deploy what it writes`() =
        runComposeUiTest {
            launchWithProject(project)
            openDeployments()
            createDeployment("Dist", "", "dist", auto = true)
            closeDeployments()
            project.file("src/a.txt", "a")
            awaitCondition { project.root.resolve("dist/src/a.txt").exists() }
            project.file("src/b.txt", "b")
            awaitCondition { project.root.resolve("dist/src/b.txt").exists() }
            assertTrue(!project.root.resolve("dist/dist").exists())
            openDeployments()
            assertContains(awaitDeploymentStatus("Dist"), "Deployed 1 file")
        }

    @Test
    fun `an automatic deployment that fails shows why and keeps working`() =
        runComposeUiTest {
            val home = scratch.resolve("home").createDirectories()
            val ssh = fakeSsh(scratch, home, failure = "Connection refused")
            if (ssh != null) {
                launchWithProject(project, ssh = ssh)
                openDeployments()
                createDeployment("Remote", "docs", "staging:site", auto = true)
                closeDeployments()
                project.root.resolve("docs/guide.md").writeText("one")
                openDeployments()
                awaitCondition {
                    runCatching {
                        awaitDeploymentStatus(
                            "Remote",
                        )
                    }.getOrDefault("").contains("Connection refused")
                }
                closeDeployments()
                project.root.resolve("docs/guide.md").writeText("two")
                openDeployments()
                awaitTag(AppTags.deploymentStatus("Remote"))
            }
        }
}
