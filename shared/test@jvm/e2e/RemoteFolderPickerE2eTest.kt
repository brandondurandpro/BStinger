package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import kotlin.test.assertTrue

/** The remote folder browser: SSH home, click-through navigation, path entry and opening the chosen folder. */
@OptIn(ExperimentalTestApi::class)
class RemoteFolderPickerE2eTest {
    private var root: Path? = null
    private var demo: Path? = null

    @BeforeTest
    fun prepare() {
        if (!RemoteTestEnvironment.available) return
        RemoteTestEnvironment.prepare()
        val workspace = Files.createTempDirectory(RemoteTestEnvironment.home, "picker-")
        val project = workspace.resolve("demo")
        project.resolve("src").createDirectories()
        project.resolve("README.md").writeText("# Remote\n")
        root = workspace
        demo = project
    }

    @AfterTest
    fun cleanup() {
        root?.toFile()?.deleteRecursively()
    }

    @Test
    fun `starts in the SSH home and opens a chosen folder`() =
        remoteUiTest {
            val workspace = checkNotNull(root)
            val project = checkNotNull(demo)
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.FOLDER_PICKER, timeout = 60_000)
            onNodeWithTag(Tags.FOLDER_PICKER_PATH)
                .assertTextContains(RemoteTestEnvironment.home.toString(), substring = true)

            awaitTag(ItemTags.folderEntry(workspace.toString())).performClick()
            awaitTag(ItemTags.folderEntry(project.toString())).performClick()
            awaitTag(Tags.FOLDER_PICKER_COUNT)
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).assertTextContains(project.toString(), substring = true)
            onNodeWithTag(Tags.FOLDER_PICKER_OPEN).performClick()

            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
            assertTrue(app.state.isRemote)
        }

    @Test
    fun `a path that does not exist is reported, then a good path recovers`() =
        remoteUiTest {
            val workspace = checkNotNull(root)
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.FOLDER_PICKER, timeout = 60_000)

            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performClick()
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextClearance()
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextInput("/does/not/exist")
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performImeAction()
            awaitTag(Tags.FOLDER_PICKER_ERROR)
                .assertTextContains("Directory not found", substring = true)

            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextClearance()
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextInput(RemoteTestEnvironment.home.toString())
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).performImeAction()
            awaitNoTag(Tags.FOLDER_PICKER_ERROR)
            awaitTag(ItemTags.folderEntry(workspace.toString()))
        }

    @Test
    fun `cancelling the folder browser keeps the remote welcome screen`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, null)
            awaitTag(Tags.FOLDER_PICKER, timeout = 60_000)
            onNodeWithTag(Tags.FOLDER_PICKER_CANCEL).performClick()
            awaitNoTag(Tags.FOLDER_PICKER)
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
            awaitTag(Tags.FOLDER_PICKER)
            onNodeWithTag(Tags.FOLDER_PICKER_PATH).assertTextContains(project.toString(), substring = true)
            onNodeWithTag(Tags.FOLDER_PICKER_CANCEL).performClick()
            awaitNoTag(Tags.FOLDER_PICKER)
        }
}
