package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Always Select Opened File": the active file is selected (and revealed) in the project tree. */
@OptIn(ExperimentalTestApi::class)
class AutoSelectOpenedFileE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun path(relative: String) = project.root.resolve(relative).toString()

    private fun ComposeUiTest.gotoFile(app: E2eApp, query: String, relative: String) {
        runOnUiThread { app.project.showPopup(PopupKind.GotoFile) }
        awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput(query)
        awaitTag(TreeTags.gotoFileItem(relative))
        onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
        awaitTag(EditorTags.tab(relative))
    }

    @Test
    fun `the option is on by default and reveals a file opened from Go to File`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOn()
            gotoFile(app, "Strings", "src/main/kotlin/util/Strings.kt")
            // Its directories were expanded and its row is selected and visible.
            awaitTag(TreeTags.treeRow("src/main/kotlin/util/Strings.kt")).assertIsDisplayed()
            awaitCondition { app.project.tree.selectedPath == path("src/main/kotlin/util/Strings.kt") }
        }

    @Test
    fun `switching tabs selects the matching file in the tree`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            awaitCondition { app.project.tree.selectedPath == path("docs/guide.md") }
            onNodeWithTag(EditorTags.tab("README.md")).performClick()
            awaitCondition { app.project.tree.selectedPath == path("README.md") }
        }

    @Test
    fun `when the option is off the tree selection does not follow the editor`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).performClick()
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOff()
            onNodeWithTag(TreeTags.treeRow("README.md")).performClick()
            gotoFile(app, "Strings", "src/main/kotlin/util/Strings.kt")
            waitForIdle()
            assertEquals(path("README.md"), app.project.tree.selectedPath)
            awaitNoTag(TreeTags.treeRow("src/main/kotlin/util/Strings.kt"))
            // Turning it back on selects the file being edited right away.
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).performClick()
            onNodeWithTag(TreeTags.TREE_AUTO_SELECT).assertIsOn()
            awaitTag(TreeTags.treeRow("src/main/kotlin/util/Strings.kt")).assertIsDisplayed()
            awaitCondition { app.project.tree.selectedPath == path("src/main/kotlin/util/Strings.kt") }
        }
}
