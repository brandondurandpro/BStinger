package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.synchronize
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class IdeFeaturesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.rightClick(tag: String) {
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.typeInEditor(key: Key) {
        onNodeWithTag(Tags.EDITOR).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun `closing a modified tab saves it automatically`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("docs/guide.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeInEditor(Key.X)
            onNodeWithTag(Tags.tabClose("docs/guide.md")).performClick()
            awaitCondition { project.read("docs/guide.md").startsWith("xGuide") }
        }

    @Test
    fun `closing the project saves modified files`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeInEditor(Key.Y)
            runOnUiThread { app.state.closeProject() }
            awaitTag(Tags.WELCOME)
            assertIs<Screen.Welcome>(app.state.screen)
            awaitCondition { project.read("README.md").startsWith("y# Demo") }
        }

    @Test
    fun `Ctrl+E shows recent files and Enter goes back to the previous one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.E) } }
            awaitTag(Tags.RECENT_FILES)
            onNodeWithTag(ItemTags.gotoFileItem("README.md")).assertIsDisplayed()
            onNodeWithTag(Tags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(Tags.RECENT_FILES)
            assertEquals(
                "README.md",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
        }

    @Test
    fun `Alt+F1 reveals the current file in the project view`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            runOnUiThread { app.project.showPopup(org.example.kotlineditor.state.PopupKind.GotoFile) }
            awaitTag(Tags.GOTO_FILE_INPUT).performTextInput("Strings")
            awaitTag(ItemTags.gotoFileItem("src/main/kotlin/util/Strings.kt")).performClick()
            awaitEditorReady()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F1) } }
            awaitTag(Tags.treeRow("src/main/kotlin/util/Strings.kt")).assertIsDisplayed()
            awaitCondition {
                app.project.tree.selectedPath
                    ?.endsWith("Strings.kt") == true
            }
        }

    @Test
    fun `context menu creates a file in nested directories and opens it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            rightClick(Tags.treeRow("docs"))
            awaitTag(Tags.MENU_NEW_FILE).performClick()
            awaitTag(Tags.FILE_DIALOG_INPUT).performTextInput("api/Endpoints.kt")
            onNodeWithTag(Tags.FILE_DIALOG_OK).performClick()
            awaitNoTag(Tags.FILE_DIALOG)
            assertTrue(project.root.resolve("docs/api/Endpoints.kt").exists())
            awaitTag(Tags.tab("docs/api/Endpoints.kt"))
            awaitTag(Tags.treeRow("docs/api/Endpoints.kt")).assertIsDisplayed()
            assertEquals(
                "Endpoints.kt",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
        }

    @Test
    fun `creating a file that already exists shows an error`() =
        runComposeUiTest {
            launchWithProject(project)
            rightClick(Tags.treeRow("README.md"))
            awaitTag(Tags.MENU_NEW_FILE).performClick()
            awaitTag(Tags.FILE_DIALOG_INPUT).performTextInput("build.gradle.kts")
            onNodeWithTag(Tags.FILE_DIALOG_OK).performClick()
            awaitTag(Tags.FILE_DIALOG_ERROR).assertIsDisplayed()
        }

    @Test
    fun `context menu creates a directory`() =
        runComposeUiTest {
            launchWithProject(project)
            rightClick(Tags.treeRow(project.root.fileName.toString()))
            awaitTag(Tags.MENU_NEW_DIRECTORY).performClick()
            awaitTag(Tags.FILE_DIALOG_INPUT).performTextInput("assets")
            onNodeWithTag(Tags.FILE_DIALOG_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(Tags.treeRow("assets"))
            assertTrue(project.root.resolve("assets").exists())
        }

    @Test
    fun `renaming an open file keeps it open under its new name`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            rightClick(Tags.treeRow("docs/guide.md"))
            awaitTag(Tags.MENU_RENAME).performClick()
            awaitTag(Tags.FILE_DIALOG_INPUT).performTextClearance()
            onNodeWithTag(Tags.FILE_DIALOG_INPUT).performTextInput("manual.md")
            onNodeWithTag(Tags.FILE_DIALOG_OK).performClick()
            awaitTag(Tags.tab("docs/manual.md"))
            awaitNoTag(Tags.tab("docs/guide.md"))
            awaitTag(Tags.treeRow("docs/manual.md"))
            assertFalse(project.root.resolve("docs/guide.md").exists())
            assertEquals(
                listOf("manual.md"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
        }

    @Test
    fun `deleting a directory removes it from disk, the tree and the tabs`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            openFromTree("README.md")
            rightClick(Tags.treeRow("docs"))
            awaitTag(Tags.MENU_DELETE).performClick()
            awaitTag(Tags.FILE_DIALOG)
            onNodeWithText("Delete directory 'docs'?").assertIsDisplayed()
            onNodeWithTag(Tags.FILE_DIALOG_OK).performClick()
            awaitNoTag(Tags.treeRow("docs"))
            assertFalse(project.root.resolve("docs").exists())
            assertEquals(
                listOf("README.md"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
        }

    @Test
    fun `the toolbar shows the current Git branch`() =
        runComposeUiTest {
            project.file(".git/HEAD", "ref: refs/heads/feature/editor\n")
            launchWithProject(project)
            awaitTag(Tags.GIT_BRANCH)
            onNodeWithText("feature/editor").assertIsDisplayed()
        }

    @Test
    fun `synchronizing reloads files changed on disk and refreshes the tree`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            project.file("docs/guide.md", "Rewritten outside\n")
            project.file("Added.kt", "class Added")
            runOnUiThread { app.project.synchronize() }
            awaitTag(Tags.treeRow("Added.kt"))
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.lines
                    ?.firstOrNull() == "Rewritten outside"
            }
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.line(0)).assertTextContains("Rewritten outside", substring = true)
                }.isSuccess
            }
        }

    @Test
    fun `synchronizing never overwrites unsaved edits`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeInEditor(Key.Z)
            project.file("README.md", "changed on disk\n")
            runOnUiThread { app.project.synchronize() }
            waitForIdle()
            assertEquals(
                "z# Demo",
                app.project.editor.activeTab!!
                    .document!!
                    .lines[0],
            )
        }

    @Test
    fun `Alt+Shift+period and Alt+Shift+comma zoom the editor font, Alt+Shift+0 resets it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            val before = onNodeWithTag(Tags.line(0)).fetchSemanticsNode().size.height
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Period) } }
                withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Period) } }
            }
            awaitCondition { app.state.editorFontSize == 15f }
            waitForIdle()
            assertTrue(onNodeWithTag(Tags.line(0)).fetchSemanticsNode().size.height > before)
            onNodeWithTag(
                Tags.EDITOR,
            ).performKeyInput { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Comma) } } }
            awaitCondition { app.state.editorFontSize == 14f }
            onNodeWithTag(
                Tags.EDITOR,
            ).performKeyInput { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Zero) } } }
            awaitCondition { app.state.editorFontSize == 13f }
            waitForIdle()
            assertEquals(before, onNodeWithTag(Tags.line(0)).fetchSemanticsNode().size.height)
        }

    @Test
    fun `Ctrl+wheel zooms instead of scrolling`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag(Tags.EDITOR).performMouseInput {
                moveTo(center)
                scroll(-1f)
            }
            onNodeWithTag(Tags.EDITOR).performKeyInput { keyUp(Key.CtrlLeft) }
            awaitCondition { app.state.editorFontSize == 14f }
        }
}
