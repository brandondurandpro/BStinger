package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Font size, "Always Select Opened File" and the tool window layout survive a restart of the IDE. */
@OptIn(ExperimentalTestApi::class)
class PreferencesE2eTest {
    private val project = SampleProject.standard()
    private val dir = Files.createTempDirectory("kotlin-editor-prefs").toFile()
    private val file = dir.resolve("preferences.properties")

    @AfterTest
    fun cleanup() {
        project.delete()
        dir.deleteRecursively()
    }

    @Test
    fun `font size, Always Select Opened File and panels are restored in the next session`() {
        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            runOnUiThread { app.state.zoomEditor(2f) }
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).performClick()
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            runOnUiThread { app.project.toolWindows.move(ToolWindow.Project, Dock.Right, first = true) }
            awaitCondition { FilePreferenceStore(file).load()["layout.dock.Project"] == "Right" }
            awaitCondition { FilePreferenceStore(file).load()[PreferenceKeys.AUTO_SELECT_OPENED_FILE] == "false" }
            awaitCondition { FilePreferenceStore(file).load()[PreferenceKeys.EDITOR_FONT_SIZE] == "15.0" }
        }

        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(15f, app.state.editorFontSize)
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOff()
            assertEquals(Dock.Right, app.project.toolWindows.dockOf(ToolWindow.Project))
            assertEquals(true, app.project.toolWindows.isVisible(ToolWindow.Git))
        }
    }

    @Test
    fun `the first launch uses the defaults`() =
        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(AppState.DEFAULT_EDITOR_FONT_SIZE, app.state.editorFontSize)
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOn()
            assertEquals(Dock.Left, app.project.toolWindows.dockOf(ToolWindow.Project))
        }

    @Test
    fun `corrupt values are ignored`() =
        runComposeUiTest {
            file.writeText(
                """
                editor.fontSize=huge
                project.autoSelectOpenedFile=2
                layout.dock.Git=Moon
                """.trimIndent(),
            )
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(AppState.DEFAULT_EDITOR_FONT_SIZE, app.state.editorFontSize)
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOn()
            assertEquals(Dock.Left, app.project.toolWindows.dockOf(ToolWindow.Git))
        }

    @Test
    fun `an out of range font size is clamped`() =
        runComposeUiTest {
            file.writeText("editor.fontSize=500\n")
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(AppState.MAX_EDITOR_FONT_SIZE, app.state.editorFontSize)
        }
}
