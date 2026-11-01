package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Open Project dialog on a remote server: it starts in the SSH user's home (or the open project), Home and `~`
 * stand for that home, a typed path is checked without Enter, and the chosen directory opens on the host.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteOpenProjectE2eTest {
    private var root: Path? = null
    private var demo: Path? = null

    private val home get() = RemoteTestEnvironment.home.toString()

    @BeforeTest
    fun prepare() {
        if (!RemoteTestEnvironment.available) return
        RemoteTestEnvironment.prepare()
        val workspace = Files.createTempDirectory(RemoteTestEnvironment.home, "picker-")
        val project = workspace.resolve("demo")
        project.resolve("src").createDirectories()
        project.resolve("README.md").writeText("# Remote\n")
        workspace.resolve("notes.txt").writeText("not a directory\n")
        root = workspace
        demo = project
    }

    @AfterTest
    fun cleanup() {
        root?.toFile()?.deleteRecursively()
    }

    @Test
    fun `starts in the SSH home and opens a chosen directory`() =
        remoteUiTest {
            val workspace = checkNotNull(root)
            val project = checkNotNull(demo)
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)
            awaitCondition { shownDirectoryInProjectPicker() == home }
            onNodeWithText("Choose a project directory on tester@127.0.0.1", substring = true).assertExists()

            awaitTag(ItemTags.pickerDirectory(workspace.toString())).performDoubleClick()
            awaitCondition { shownDirectoryInProjectPicker() == workspace.toString() }
            awaitTag(ItemTags.pickerDirectory(project.toString())).performClick()
            awaitEnabledOpen()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()

            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
            assertTrue(app.state.isRemote)
        }

    @Test
    fun `typing a directory path lists it without pressing Enter`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)

            replaceFolderPath(project.toString())
            // No Enter: the debounced check lists the directory's subdirectories by itself.
            awaitTag(ItemTags.pickerDirectory(project.resolve("src").toString()), timeout = 15_000)
            assertEquals(project.toString(), shownDirectoryInProjectPicker())
            awaitEnabledOpen()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
        }

    @Test
    fun `a path typed in chunks recovers from a bad path without Enter`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)

            typeFolderPathInChunks("/does", "/not", "/exist")
            awaitTag(Tags.OPEN_PROJECT_ERROR, timeout = 15_000)
                .assertTextContains("Directory not found", substring = true)
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsNotEnabled()

            val path = project.toString()
            typeFolderPathInChunks(path.dropLast(5), path.takeLast(5))
            awaitTag(ItemTags.pickerDirectory(project.resolve("src").toString()), timeout = 15_000)
            awaitNoTag(Tags.OPEN_PROJECT_ERROR)
        }

    @Test
    fun `Open waits for the typed path to be checked and the text is kept`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)
            awaitTag(Tags.OPEN_PROJECT_COUNT)

            replaceFolderPath("$project/")
            awaitTag(Tags.OPEN_PROJECT_CHECKING)
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsNotEnabled()
            awaitTag(ItemTags.pickerDirectory(project.resolve("src").toString()), timeout = 15_000)
            assertEquals("$project/", shownDirectoryInProjectPicker(), "the trailing slash stays as typed")
            awaitEnabledOpen()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
        }

    @Test
    fun `the tilde is the SSH home and Home goes back to it`() =
        remoteUiTest {
            val workspace = checkNotNull(root)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)

            val relative = RemoteTestEnvironment.home.relativize(workspace).toString()
            replaceFolderPath("~/$relative")
            awaitTag(ItemTags.pickerDirectory(checkNotNull(demo).toString()), timeout = 15_000)
            assertEquals("~/$relative", shownDirectoryInProjectPicker())

            onNodeWithTag(Tags.OPEN_PROJECT_HOME).performClick()
            awaitCondition { shownDirectoryInProjectPicker() == home }
            awaitTag(ItemTags.pickerDirectory(workspace.toString()))
        }

    @Test
    fun `a path that does not exist or is a file is reported, then a good path recovers`() =
        remoteUiTest {
            val workspace = checkNotNull(root)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)

            navigateToInProjectPicker("/does/not/exist")
            awaitTag(Tags.OPEN_PROJECT_ERROR).assertTextContains("Directory not found", substring = true)
            val file = workspace.resolve("notes.txt").toString()
            navigateToInProjectPicker(file)
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.OPEN_PROJECT_ERROR).assertTextContains(file, substring = true)
                }.isSuccess
            }
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsNotEnabled()

            showDirectoryInProjectPicker(home)
            awaitNoTag(Tags.OPEN_PROJECT_ERROR)
            awaitTag(ItemTags.pickerDirectory(workspace.toString()))
        }

    @Test
    fun `cancelling the dialog keeps the remote welcome screen`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)
            onNodeWithTag(Tags.OPEN_PROJECT_CANCEL).performClick()
            awaitNoTag(Tags.OPEN_PROJECT)
            assertTrue(app.state.screen == Screen.Welcome)
            assertTrue(app.state.isRemote)
        }

    @Test
    fun `opening from the project menu starts at the open project`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.toString())
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            onNodeWithTag(Tags.PROJECT_OPEN_OTHER).performClick()
            awaitTag(Tags.OPEN_PROJECT)
            awaitCondition { shownDirectoryInProjectPicker() == project.toString() }
            onNodeWithTag(Tags.OPEN_PROJECT_CANCEL).performClick()
            awaitNoTag(Tags.OPEN_PROJECT)
        }

    @Test
    fun `a remote directory is not remembered as the start of the local dialog`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            val app = launchApp()
            awaitCondition { app.state.serverHome != null }
            val localHome = app.state.serverHome
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.OPEN_PROJECT, timeout = 60_000)
            showDirectoryInProjectPicker(project.toString())
            awaitEnabledOpen()
            onNodeWithTag(Tags.OPEN_PROJECT_OPEN).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)

            onNodeWithTag(Tags.TOP_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DISCONNECT).performClick()
            awaitTag(Tags.WELCOME)
            awaitCondition { !app.state.isRemote }
            openProjectPickerFromWelcome()
            awaitCondition { shownDirectoryInProjectPicker() == localHome }
        }

    private fun ComposeUiTest.awaitEnabledOpen() {
        awaitCondition { runCatching { onNodeWithTag(Tags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
    }
}
