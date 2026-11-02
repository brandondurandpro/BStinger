package org.example.kotlineditor.e2e

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class SearchE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `Ctrl+F finds occurrences in the current file and Enter jumps between them`() =
        runComposeUiTest {
            project.file("Find.kt", textOf("val a = 1", "val b = a + a", "// a again", ""))
            val app = launchWithProject(project)
            openFromTree("Find.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("val")
            awaitCondition { app.project.find.matches.size == 2 }
            onNodeWithTag(EditorTags.FIND_BAR_COUNT).assertTextContains("/2", substring = true)
            onNodeWithTag(EditorTags.FIND_BAR_INPUT).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("val", doc.selectedText())
            val first = doc.caret
            onNodeWithTag(EditorTags.FIND_BAR_INPUT).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("val", doc.selectedText())
            assertEquals(1, kotlin.math.abs(doc.caret.line - first.line))
            onNodeWithTag(EditorTags.FIND_BAR_NEXT).performClick()
            waitForIdle()
            assertEquals(first, doc.caret)
        }

    @Test
    fun `find bar reports no results and supports match case`() =
        runComposeUiTest {
            project.file("Case.txt", "Hello hello HELLO\n")
            val app = launchWithProject(project)
            openFromTree("Case.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("hello")
            awaitCondition { app.project.find.matches.size == 3 }
            app.project.find.matchCase = true
            awaitCondition { app.project.find.matches.size == 1 }
            onNodeWithTag(EditorTags.FIND_BAR_INPUT).performTextClearance()
            onNodeWithTag(EditorTags.FIND_BAR_INPUT).performTextInput("absent")
            awaitCondition {
                runCatching {
                    onNodeWithTag(EditorTags.FIND_BAR_COUNT).assertTextContains("0 results")
                }.isSuccess
            }
            onNodeWithTag(EditorTags.FIND_BAR_CLOSE).performClick()
            awaitNoTag(EditorTags.FIND_BAR)
        }

    @Test
    fun `Ctrl+F prefills the find bar with the selection`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(EditorTags.EDITOR).performClick()
            runOnUiThread { doc.select(TextPos(7, 8), TextPos(7, 16)) }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(EditorTags.FIND_BAR_INPUT)
            assertEquals("greeting", app.project.find.query)
            awaitCondition { app.project.find.matches.size == 2 }
        }

    @Test
    fun `Go to File finds files by fuzzy name and opens them`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(
                TreeTags.TREE,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput("strkt")
            awaitTag(TreeTags.gotoFileItem("src/main/kotlin/util/Strings.kt")).assertIsDisplayed()
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(EditorTags.tab("src/main/kotlin/util/Strings.kt"))
            awaitNoTag(TreeTags.GOTO_FILE)
            assertEquals(
                "Strings.kt",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
        }

    @Test
    fun `Enter right after typing in Go to File opens the match of the typed name, not of the previous list`() =
        runComposeUiTest {
            // Enough files for the search to take a while: Enter comes before its results (5 000 were not enough).
            repeat(GOTO_FILE_FILLER) { project.file("src/filler/Filler$it.kt", "") }
            val app = launchWithProject(project)
            runOnUiThread { app.project.showPopup(org.example.kotlineditor.state.PopupKind.GotoFile) }
            awaitTag(TreeTags.GOTO_FILE_INPUT)
            awaitCondition { app.project.fileIndex.files != null }
            // The list of every file is shown first.
            waitForIdle()
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performTextInput("strkt")
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(EditorTags.tab("src/main/kotlin/util/Strings.kt"))
            awaitNoTag(TreeTags.GOTO_FILE)
            assertEquals(
                "Strings.kt",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
        }

    @Test
    fun `Go to File ignores VCS directories and can be dismissed with Escape`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            runOnUiThread { app.project.showPopup(org.example.kotlineditor.state.PopupKind.GotoFile) }
            awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput("config")
            awaitCondition { app.project.fileIndex.files != null }
            waitForIdle()
            awaitNoTag(TreeTags.gotoFileItem(".git/config"))
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.GOTO_FILE)
            assertNull(app.project.popup)
        }

    @Test
    fun `double Shift opens Go to File`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val document =
                app.project.editor.activeTab!!
                    .document!!
            val text = document.text()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            awaitTag(TreeTags.GOTO_FILE_INPUT).assertIsFocused()
            assertEquals(text, document.text())
        }

    @Test
    fun `double Shift with a real key event does not type into the editor`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val document =
                app.project.editor.activeTab!!
                    .document!!
            val text = document.text()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            // Compose reports a key without a character (here Shift) with CHAR_UNDEFINED.
            val scene = (this as SkikoComposeUiTest).scene
            runOnUiThread {
                repeat(2) {
                    scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyDown, codePoint = 0xFFFF))
                    scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyUp, codePoint = 0xFFFF))
                }
            }
            awaitTag(TreeTags.GOTO_FILE_INPUT)
            assertEquals(text, document.text())
        }

    @Test
    fun `Ctrl+P opens Go to File`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).assertIsFocused()
        }

    @Test
    fun `Ctrl+P opens Go to File when no file is open`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            onNodeWithTag(AppTags.PROJECT).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).assertIsFocused()
        }

    @Test
    fun `Ctrl+Shift+F opens Find in Files when no file is open`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            onNodeWithTag(AppTags.PROJECT).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).assertIsFocused()
        }

    @Test
    fun `Ctrl+P still works after closing the last tab`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F4) } }
            awaitTag(AppTags.EDITOR_EMPTY)
            onNodeWithTag(AppTags.PROJECT).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).assertIsFocused()
        }

    @Test
    fun `search shortcuts still work after dismissing a popup when no file is open`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            onNodeWithTag(AppTags.PROJECT).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.GOTO_FILE)
            onNodeWithTag(AppTags.PROJECT).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.FIND_IN_FILES)
            onNodeWithTag(AppTags.PROJECT).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(TreeTags.GOTO_FILE_INPUT).assertIsFocused()
            assertNull(app.project.editor.activeTab)
        }

    @Test
    fun `Find in Files lists matches across the project and opens the selected one at its line`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(
                TreeTags.TREE,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(TreeTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files")
            onNodeWithTag(TreeTags.findInFilesItem("src/main/kotlin/App.kt", 7)).performClick()
            awaitTag(EditorTags.tab("src/main/kotlin/App.kt"))
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.selectedText() == "Hello"
            }
            assertEquals(
                7,
                app.project.editor.activeTab!!
                    .document!!
                    .caret
                    .line,
            )
        }

    @Test
    fun `Find in Files counts its matches and files in correct English`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(
                TreeTags.TREE,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
            val input = awaitTag(TreeTags.FIND_IN_FILES_INPUT)
            val count = onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT)
            input.performTextInput("Hello from")
            awaitCondition { runCatching { count.assertTextEquals("1 match in 1 file") }.isSuccess }
            input.performTextClearance()
            // "Guide" and "guide", both in docs/guide.md.
            input.performTextInput("guide")
            awaitCondition { runCatching { count.assertTextEquals("2 matches in 1 file") }.isSuccess }
            input.performTextClearance()
            input.performTextInput("no-such-text-anywhere")
            awaitCondition { runCatching { count.assertTextEquals("No matches") }.isSuccess }
        }

    @Test
    fun `Ctrl+G goes to a line and column`() =
        runComposeUiTest {
            project.file("Lines.txt", (1..500).joinToString("\n") { "line $it" })
            val app = launchWithProject(project)
            openFromTree("Lines.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
            awaitTag(AppTags.GOTO_LINE_INPUT).performTextClearance()
            onNodeWithTag(AppTags.GOTO_LINE_INPUT).performTextInput("420:3")
            onNodeWithTag(AppTags.GOTO_LINE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(AppTags.GOTO_LINE)
            assertEquals(
                TextPos(419, 2),
                app.project.editor.activeTab!!
                    .document!!
                    .caret,
            )
            awaitTag(EditorTags.line(419)).assertIsDisplayed()
            onNodeWithTag(AppTags.STATUS_CARET).assertTextContains("420:3", substring = true)
        }

    private companion object {
        const val GOTO_FILE_FILLER = 40_000
    }
}
