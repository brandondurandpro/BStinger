package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
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
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
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
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(Tags.FIND_BAR_INPUT).performTextInput("val")
            awaitCondition { app.project.find.matches.size == 2 }
            onNodeWithTag(Tags.FIND_BAR_COUNT).assertTextContains("/2", substring = true)
            onNodeWithTag(Tags.FIND_BAR_INPUT).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("val", doc.selectedText())
            val first = doc.caret
            onNodeWithTag(Tags.FIND_BAR_INPUT).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("val", doc.selectedText())
            assertEquals(1, kotlin.math.abs(doc.caret.line - first.line))
            onNodeWithTag(Tags.FIND_BAR_NEXT).performClick()
            waitForIdle()
            assertEquals(first, doc.caret)
        }

    @Test
    fun `find bar reports no results and supports match case`() =
        runComposeUiTest {
            project.file("Case.txt", "Hello hello HELLO\n")
            val app = launchWithProject(project)
            openFromTree("Case.txt")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(Tags.FIND_BAR_INPUT).performTextInput("hello")
            awaitCondition { app.project.find.matches.size == 3 }
            app.project.find.matchCase = true
            awaitCondition { app.project.find.matches.size == 1 }
            onNodeWithTag(Tags.FIND_BAR_INPUT).performTextClearance()
            onNodeWithTag(Tags.FIND_BAR_INPUT).performTextInput("absent")
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.FIND_BAR_COUNT).assertTextContains("0 results")
                }.isSuccess
            }
            onNodeWithTag(Tags.FIND_BAR_CLOSE).performClick()
            awaitNoTag(Tags.FIND_BAR)
        }

    @Test
    fun `Ctrl+F prefills the find bar with the selection`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.select(TextPos(7, 8), TextPos(7, 16)) }
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(Tags.FIND_BAR_INPUT)
            assertEquals("greeting", app.project.find.query)
            awaitCondition { app.project.find.matches.size == 2 }
        }

    @Test
    fun `Go to File finds files by fuzzy name and opens them`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(
                Tags.TREE,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
            awaitTag(Tags.GOTO_FILE_INPUT).performTextInput("strkt")
            awaitTag(ItemTags.gotoFileItem("src/main/kotlin/util/Strings.kt")).assertIsDisplayed()
            onNodeWithTag(Tags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(Tags.tab("src/main/kotlin/util/Strings.kt"))
            awaitNoTag(Tags.GOTO_FILE)
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
            awaitTag(Tags.GOTO_FILE_INPUT).performTextInput("config")
            awaitCondition { app.project.fileIndex.files != null }
            waitForIdle()
            awaitNoTag(ItemTags.gotoFileItem(".git/config"))
            onNodeWithTag(Tags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(Tags.GOTO_FILE)
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
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            awaitTag(Tags.GOTO_FILE_INPUT).assertIsFocused()
            assertEquals(text, document.text())
        }

    @Test
    fun `Ctrl+P opens Go to File`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
            awaitTag(Tags.GOTO_FILE_INPUT).assertIsFocused()
        }

    @Test
    fun `Find in Files lists matches across the project and opens the selected one at its line`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(
                Tags.TREE,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
            awaitTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            onNodeWithTag(Tags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files")
            onNodeWithTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7)).performClick()
            awaitTag(Tags.tab("src/main/kotlin/App.kt"))
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
    fun `Ctrl+G goes to a line and column`() =
        runComposeUiTest {
            project.file("Lines.txt", (1..500).joinToString("\n") { "line $it" })
            val app = launchWithProject(project)
            openFromTree("Lines.txt")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
            awaitTag(Tags.GOTO_LINE_INPUT).performTextClearance()
            onNodeWithTag(Tags.GOTO_LINE_INPUT).performTextInput("420:3")
            onNodeWithTag(Tags.GOTO_LINE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(Tags.GOTO_LINE)
            assertEquals(
                TextPos(419, 2),
                app.project.editor.activeTab!!
                    .document!!
                    .caret,
            )
            awaitTag(Tags.line(419)).assertIsDisplayed()
            onNodeWithTag(Tags.STATUS_CARET).assertTextContains("420:3", substring = true)
        }
}
