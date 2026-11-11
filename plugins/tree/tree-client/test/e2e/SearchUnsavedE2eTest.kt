package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Find in Files about the open documents modified in memory: their hits replace the disk ones of the same
 * files (what the query answers is what the editor shows, never twice for one file), until the file is saved.
 */
@OptIn(ExperimentalTestApi::class)
class SearchUnsavedE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT)
    }

    // A line break at the end of the first line (like a user adding a line), then [text] typed on it.
    private fun ComposeUiTest.typeOnSecondLine(app: E2eApp, text: String) {
        onNodeWithTag(EditorTags.EDITOR).performClick()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.Enter) }
        for (char in text) {
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(keyOf(char)) }
        }
        val document =
            app.project.editor.activeTab!!
                .document!!
        awaitCondition { document.isModified }
        waitForIdle()
    }

    private fun ComposeUiTest.saveFromEditor() {
        onNodeWithTag(EditorTags.EDITOR).performClick()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
    }

    @Test
    fun `a line typed in an unsaved document is found before it is saved and, once, after saving`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            typeOnSecondLine(app, "zzfreshzz")

            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("zzfreshzz")
            // Line 1 (zero-based), the one the edit added: the buffer is searched, the word being not on disk.
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("1 match in 1 file")

            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.FIND_IN_FILES)
            saveFromEditor()
            awaitCondition {
                !app.project.editor.activeTab!!
                    .document!!
                    .isModified
            }
            awaitCondition { project.read("docs/guide.md").contains("zzfreshzz") }

            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("zzfreshzz")
            // Saved: the tab is modified no more, the hit now comes from the disk search only (once).
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("1 match in 1 file")
        }

    @Test
    fun `the hits of a modified document replace the disk ones of the same file, no duplicates`() =
        runComposeUiTest {
            // guide.md on the disk has 2 case-insensitive matches of "guide" ("Guide", "... guide"); the buffer
            // with the added line has 3. Without the replacement the merged list would answer 5: assert
            // exactly the buffer's 3 hits.
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            typeOnSecondLine(app, "guide")

            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("guide")
            for (line in 0..2) {
                awaitTag(TreeTags.findInFilesItem("docs/guide.md", line))
            }
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("3 matches in 1 file")
        }

    private companion object {
        private val LETTERS =
            listOf(
                Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
                Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
            )

        // Only lowercase words are typed (a to z), to keep the keys simple.
        private fun keyOf(char: Char): Key = LETTERS[char - 'a']
    }
}
