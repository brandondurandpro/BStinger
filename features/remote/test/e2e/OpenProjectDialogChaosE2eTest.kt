package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Open Project dialog while the network or the server fails: its listings go through the same client as the
 * editor, so a fired navigation may hang in the air while the answer of an older one arrives late.
 */
@OptIn(ExperimentalTestApi::class)
class OpenProjectDialogChaosE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject().apply { file("Other.kt", "class Other") }
    private val empty = SampleProject()

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        empty.delete()
    }

    private fun ComposeUiTest.typeNewFolder(name: String) {
        onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_FOLDER).performClick()
        awaitTag(RemoteTags.OPEN_PROJECT_NEW_NAME).performTextInput(name)
    }

    private fun ComposeUiTest.openFromProjectMenu() {
        onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
        awaitTag(AppTags.PROJECT_OPEN_OTHER).performClick()
        awaitTag(RemoteTags.OPEN_PROJECT)
    }

    private fun srcRowOf(sample: SampleProject) = RemoteTags.pickerDirectory(sample.root.resolve("src").toString())

    @Test
    fun `the navigation typed while the network is down lists the directories once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            app.network.goDown()
            awaitReconnecting()
            navigateToInProjectPicker(other.path)
            app.network.comeBack()
            awaitCondition { shownDirectoryInProjectPicker() == other.path }
        }

    @Test
    fun `on a slow link the answer of an older navigation never overrides the newer one`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(other.path)
            app.network.latencyMs = 400
            showDirectoryInProjectPicker(project.path)
            // The listings of the other directory are late; the dialog shows the directory named by the field.
            awaitTag(srcRowOf(project)).assertIsDisplayed()
            awaitCondition { shownDirectoryInProjectPicker() == project.path }
        }

    @Test
    fun `on a slow link the Open button pressed right after Enter does not open the directory shown before`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(other.path)
            app.network.latencyMs = 400
            navigateToInProjectPicker(project.path)
            // The listing of the typed path is still in the air: Open waits for it, it does not open the other one.
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            onNodeWithTag(RemoteTags.OPEN_PROJECT).assertExists()
            awaitTag(srcRowOf(project))
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            awaitTag(TreeTags.treeRow("src"))
            onNodeWithTag(TreeTags.treeRow("Other.kt")).assertDoesNotExist()
        }

    @Test
    fun `the dialog still navigates after the server lost its session and restarted`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(other.path)
            app.process.restart()
            awaitReconnected()
            showDirectoryInProjectPicker(project.path)
            awaitTag(srcRowOf(project)).assertIsDisplayed()
        }

    @Test
    fun `a folder created while the network is down is created once the network is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(empty.path)
            typeNewFolder("fresh")
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_CREATE).performClick()
            app.network.comeBack()
            val created = empty.root.resolve("fresh")
            awaitCondition(SERVER_START_MS) { shownDirectoryInProjectPicker() == created.toString() }
            assertTrue(created.isDirectory(), "the folder exists")
        }

    @Test
    fun `a folder whose answer was lost exists once and the dialog is not left stuck`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(empty.path)
            typeNewFolder("fresh")
            app.network.dropFromServer = true
            onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_CREATE).performClick()
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            val created = empty.root.resolve("fresh")
            awaitCondition(SERVER_START_MS) { created.isDirectory() }
            // The dialog still works: it was not left loading, and shows the directory it was in or the new one.
            showDirectoryInProjectPicker(other.path)
            assertEquals(listOf("fresh"), empty.root.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `a folder is still created after the server restarted`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromProjectMenu()
            showDirectoryInProjectPicker(empty.path)
            app.process.restart()
            awaitReconnected()
            typeNewFolder("after")
            onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_CREATE).performClick()
            awaitCondition { empty.root.resolve("after").isDirectory() }
        }
}
