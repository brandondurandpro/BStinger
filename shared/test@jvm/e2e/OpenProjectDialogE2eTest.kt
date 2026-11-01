package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The Open Project dialog: browsing the directories of the server, from the welcome screen or the project menu. */
@OptIn(ExperimentalTestApi::class)
class OpenProjectDialogE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject().apply { file("src/Other.kt", "class Other") }
    private val empty = SampleProject()

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        empty.delete()
    }

    private fun pathInProject(relative: String): Path = project.root.resolve(relative)

    @Test
    fun `Open starts in the home directory of the server`() =
        runComposeUiTest {
            val app = launchApp()
            awaitCondition { app.state.serverHome != null }
            openProjectPickerFromWelcome()
            awaitCondition { shownDirectoryInProjectPicker() == app.state.serverHome }
        }

    // The children are sorted directories first, then by name: .git, docs, src.
    @Test
    fun `Open opens the browsed directory as a project`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(project.path)
            awaitTag(ItemTags.pickerDirectory(pathInProject("src").toString())).assertIsDisplayed()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.PROJECT)
            assertEquals(project.root.fileName.toString(), app.state.title)
            awaitCondition { app.recentPaths == listOf(project.path) }
        }

    @Test
    fun `the dialog reopens at the directory it last browsed`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(project.path)
            onNodeWithTag(Tags.OPEN_PROJECT_CANCEL).performClick()
            onNodeWithTag(Tags.WELCOME_OPEN).performClick()
            awaitTag(Tags.OPEN_PROJECT)
            awaitCondition { shownDirectoryInProjectPicker() == project.path }
            awaitTag(ItemTags.pickerDirectory(pathInProject("src").toString())).assertIsDisplayed()
        }

    @Test
    fun `a double click on a directory shows its content`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(project.path)
            val src = pathInProject("src").toString()
            // A real double click (two clicks in a row): the second one enters the directory.
            onNodeWithTag(ItemTags.pickerDirectory(src)).performDoubleClick()
            awaitCondition { shownDirectoryInProjectPicker() == src }
            awaitTag(ItemTags.pickerDirectory(pathInProject("src/main").toString())).assertIsDisplayed()
            awaitTag(ItemTags.pickerDirectory(pathInProject("src/test").toString())).assertIsDisplayed()
        }

    @Test
    fun `Up shows the parent directory`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(pathInProject("src").toString())
            onNodeWithTag(Tags.OPEN_PROJECT_UP).performClick()
            awaitCondition { shownDirectoryInProjectPicker() == project.path }
            awaitTag(ItemTags.pickerDirectory(pathInProject("src").toString())).assertIsDisplayed()
        }

    @Test
    fun `the arrows select a directory and Enter opens it as the project`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(project.path)
            // A real click focuses the row: the keys are then for the list, not for the path field.
            onNodeWithTag(ItemTags.pickerDirectory(pathInProject("docs").toString())).performMouseInput { click() }
            // Down selects the next one (src), which Enter opens as the project.
            onNodeWithTag(Tags.OPEN_PROJECT).performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag(Tags.OPEN_PROJECT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(Tags.PROJECT)
            assertEquals("src", app.state.title)
        }

    @Test
    fun `a path that is not a directory shows an error and the dialog keeps working`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectPickerFromWelcome()
            navigateToInProjectPicker("/no/such/project/directory")
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.OPEN_PROJECT_ERROR)
                        .assertTextEquals("Directory not found: /no/such/project/directory")
                }.isSuccess
            }
            assertIs<Screen.Welcome>(app.state.screen)
            // The failed navigation does not block the next one.
            showDirectoryInProjectPicker(project.path)
            awaitTag(ItemTags.pickerDirectory(pathInProject("src").toString())).assertIsDisplayed()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.PROJECT)
        }

    @Test
    fun `a directory without subdirectories shows that it is empty`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(empty.path)
            awaitTag(Tags.OPEN_PROJECT_EMPTY).assertIsDisplayed()
        }

    @Test
    fun `Escape closes the dialog`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            onNodeWithTag(Tags.OPEN_PROJECT_PATH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(Tags.OPEN_PROJECT)
        }

    @Test
    fun `Open Project in the project menu switches to the directory chosen`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            awaitTag(Tags.PROJECT_OPEN_OTHER).performClick()
            awaitTag(Tags.OPEN_PROJECT)
            showDirectoryInProjectPicker(other.path)
            awaitTag(ItemTags.pickerDirectory(other.root.resolve("src").toString())).assertIsDisplayed()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.PROJECT)
            awaitTag(Tags.treeRow("src")).assertIsDisplayed()
            assertEquals(other.root.fileName.toString(), app.state.title)
        }

    @Test
    fun `a typed path is listed after a pause, without Enter, and Open waits for it`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectPickerFromWelcome()
            awaitTag(Tags.OPEN_PROJECT_COUNT)
            onNodeWithTag(Tags.OPEN_PROJECT_PATH).performTextReplacement(project.path)
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsNotEnabled()
            awaitTag(ItemTags.pickerDirectory(pathInProject("src").toString())).assertIsDisplayed()
            awaitCondition { runCatching { onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.PROJECT)
            assertEquals(project.root.fileName.toString(), app.state.title)
        }

    @Test
    fun `Home shows the home directory of the server`() =
        runComposeUiTest {
            val app = launchApp()
            awaitCondition { app.state.serverHome != null }
            openProjectPickerFromWelcome()
            showDirectoryInProjectPicker(project.path)
            onNodeWithTag(Tags.OPEN_PROJECT_HOME).performClick()
            awaitCondition { shownDirectoryInProjectPicker() == app.state.serverHome }
        }

    @Test
    fun `a relative fragment being typed is not checked and Open stays off`() =
        runComposeUiTest {
            launchApp()
            openProjectPickerFromWelcome()
            awaitTag(Tags.OPEN_PROJECT_COUNT)
            onNodeWithTag(Tags.OPEN_PROJECT_PATH).performTextReplacement("src")
            awaitNoTag(Tags.OPEN_PROJECT_CHECKING)
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsNotEnabled()
            onNodeWithTag(Tags.OPEN_PROJECT_ERROR).assertDoesNotExist()
        }
}
