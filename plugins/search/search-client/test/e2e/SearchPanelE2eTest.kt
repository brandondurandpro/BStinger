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
import org.example.kotlineditor.ui.SearchTags
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
            awaitTag(SearchTags.TOOL_BUTTON).performClick()
            awaitNoTag(TreeTags.PROJECT_PANEL)
            awaitTag(SearchTags.PANEL)
            awaitTag(SearchTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            val search = app.project.findInFiles
            awaitCondition { search.finished && search.results.isNotEmpty() }
            onNodeWithTag(SearchTags.FIND_IN_FILES_COUNT).assertTextContains("1 match", substring = true)
            awaitTag(SearchTags.findInFilesItem("Needle.kt", 1)).performClick()
            onNodeWithTag(SearchTags.findInFilesItem("Needle.kt", 1)).assertIsSelected()
            awaitCondition { app.project.editor.activeTab != null }
            val tab = app.project.editor.activeTab
            assertNotNull(tab)
            assertEquals("Needle.kt", tab.title)
            // The panel stays open with its results.
            awaitTag(SearchTags.PANEL)
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
            awaitTag(SearchTags.PANEL)
            awaitTag(SearchTags.FIND_IN_FILES_INPUT).assertIsFocused()
            awaitNoTag(TreeTags.PROJECT_PANEL)
            // Typing goes straight to the field; Escape hides the panel.
            onNodeWithTag(SearchTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            onNodeWithTag(SearchTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(SearchTags.PANEL)
        }

    @Test
    fun `results are grouped by file and a file folds and unfolds its hits`() =
        runComposeUiTest {
            project.file("src/Alpha.kt", textOf("val needle = 1", "val needle = 2", ""))
            project.file("src/Beta.kt", textOf("val needle = 3", ""))
            val app = launchWithProject(project)
            app.project.openFindInFiles()
            awaitTag(SearchTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitTag(SearchTags.findInFilesFile("src/Alpha.kt"))
            awaitTag(SearchTags.findInFilesFile("src/Beta.kt"))
            awaitTag(SearchTags.findInFilesItem("src/Alpha.kt", 0))
            awaitTag(SearchTags.findInFilesItem("src/Alpha.kt", 1))
            awaitTag(SearchTags.findInFilesItem("src/Beta.kt", 0))
            // Folding Alpha hides its two hits and keeps Beta's.
            onNodeWithTag(SearchTags.findInFilesFile("src/Alpha.kt")).performClick()
            awaitNoTag(SearchTags.findInFilesItem("src/Alpha.kt", 0))
            awaitNoTag(SearchTags.findInFilesItem("src/Alpha.kt", 1))
            awaitTag(SearchTags.findInFilesItem("src/Beta.kt", 0))
            onNodeWithTag(SearchTags.findInFilesFile("src/Alpha.kt")).performClick()
            awaitTag(SearchTags.findInFilesItem("src/Alpha.kt", 0))
        }
}
