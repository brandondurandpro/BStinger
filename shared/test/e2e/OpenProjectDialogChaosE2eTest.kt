package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The Open Project dialog while the network or the server fails: its listings go through the same client as the
 * editor, so a fired navigation may hang in the air while the answer of an older one arrives late.
 */
@OptIn(ExperimentalTestApi::class)
class OpenProjectDialogChaosE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject().apply { file("Other.kt", "class Other") }

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
    }

    private fun ComposeUiTest.openFromProjectMenu() {
        onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
        awaitTag(Tags.PROJECT_OPEN_OTHER).performClick()
        awaitTag(Tags.OPEN_PROJECT)
    }

    private fun srcRowOf(sample: SampleProject) = ItemTags.pickerDirectory(sample.root.resolve("src").toString())

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
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            onNodeWithTag(Tags.OPEN_PROJECT).assertExists()
            awaitTag(srcRowOf(project))
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitNoTag(Tags.OPEN_PROJECT)
            awaitTag(Tags.treeRow("src"))
            onNodeWithTag(Tags.treeRow("Other.kt")).assertDoesNotExist()
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
}
