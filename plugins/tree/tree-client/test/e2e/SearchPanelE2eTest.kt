package org.example.kotlineditor.e2e

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class SearchPanelE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the search button of the left strip opens a panel that finds text and opens a hit`() =
        runComposeUiTest {
            project.file("Needle.kt", textOf("val a = 1", "val needle = 2", ""))
            val app = launchWithProject(project)
            awaitTag(TreeTags.SEARCH_TOOL_BUTTON).performClick()
            awaitNoTag(TreeTags.PROJECT_PANEL)
            awaitTag(TreeTags.SEARCH_PANEL)
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            val search = app.project.findInFiles
            awaitCondition { search.finished && search.results.isNotEmpty() }
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("1 match", substring = true)
            awaitTag(TreeTags.findInFilesItem("Needle.kt", 1)).performClick()
            onNodeWithTag(TreeTags.findInFilesItem("Needle.kt", 1)).assertIsSelected()
            awaitCondition { app.project.editor.activeTab != null }
            val tab = app.project.editor.activeTab
            assertNotNull(tab)
            assertEquals("Needle.kt", tab.title)
            // The panel stays open with its results.
            awaitTag(TreeTags.SEARCH_PANEL)
        }

    @Test
    fun `Ctrl+Shift+F shows the panel with the caret in the query field, no modal`() =
        runComposeUiTest {
            project.file("Needle.kt", textOf("val needle = 2", ""))
            launchWithProject(project)
            awaitTag(TreeTags.TREE).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            awaitTag(TreeTags.SEARCH_PANEL)
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).assertIsFocused()
            awaitNoTag(TreeTags.PROJECT_PANEL)
            // Typing goes straight to the field; Escape hides the panel.
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.SEARCH_PANEL)
        }

    @Test
    fun `results are grouped by file and a file folds and unfolds its hits`() =
        runComposeUiTest {
            project.file("src/Alpha.kt", textOf("val needle = 1", "val needle = 2", ""))
            project.file("src/Beta.kt", textOf("val needle = 3", ""))
            val app = launchWithProject(project)
            app.project.openFindInFiles()
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitTag(TreeTags.findInFilesFile("src/Alpha.kt"))
            awaitTag(TreeTags.findInFilesFile("src/Beta.kt"))
            awaitTag(TreeTags.findInFilesItem("src/Alpha.kt", 0))
            awaitTag(TreeTags.findInFilesItem("src/Alpha.kt", 1))
            awaitTag(TreeTags.findInFilesItem("src/Beta.kt", 0))
            // Folding Alpha hides its two hits and keeps Beta's.
            onNodeWithTag(TreeTags.findInFilesFile("src/Alpha.kt")).performClick()
            awaitNoTag(TreeTags.findInFilesItem("src/Alpha.kt", 0))
            awaitNoTag(TreeTags.findInFilesItem("src/Alpha.kt", 1))
            awaitTag(TreeTags.findInFilesItem("src/Beta.kt", 0))
            onNodeWithTag(TreeTags.findInFilesFile("src/Alpha.kt")).performClick()
            awaitTag(TreeTags.findInFilesItem("src/Alpha.kt", 0))
        }
}
