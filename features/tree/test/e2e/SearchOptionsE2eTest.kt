package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The options of Find in Files, driven like a user does in the popup: case is already covered by the searches of
 * [SearchE2eTest], here are the whole word, the regular expression (with the error of a broken one, and the
 * recovery), the file glob, the ignored files included and the scope. With them, the engine of the popup's
 * results: the built-in one when ripgrep cannot be there (ticket #13).
 */
@OptIn(ExperimentalTestApi::class)
class SearchOptionsE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun write(relative: String, vararg lines: String) {
        val file = project.root.resolve(relative)
        file.parent.toFile().mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT)
    }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput(text)
    }

    private fun ComposeUiTest.clearQuery() {
        onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextClearance()
    }

    // Waits until [expected] holds with the exact text of [tag], showing the popup's UI tree if it never does.
    private fun ComposeUiTest.countIs(
        tag: String = TreeTags.FIND_IN_FILES_COUNT,
        expected: androidx.compose.ui.test.SemanticsNodeInteraction.() -> Unit,
    ) {
        try {
            awaitCondition { runCatching { onNodeWithTag(tag).expected() }.isSuccess }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val tree = onRoot().printToString(maxDepth = 12)
            throw AssertionError("The text of '$tag' never matched its expectation: $tree", e)
        }
    }

    @Test
    fun `whole word finds the words, not the longer ones that contain them`() =
        runComposeUiTest {
            // A match per line: the engines count matching lines ("needle" is a word in line 0, and part of
            // "needled" in line 1, which whole words stop counting).
            write("src/Words.txt", "needle here\nneedled here\n")
            launchWithProject(project)
            openFindInFiles()
            type("needle")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 1 file") }
            awaitTag(TreeTags.FIND_IN_FILES_WHOLE_WORD).performClick()
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("1 match in 1 file") }
            awaitCondition { runCatching {
                onNodeWithTag(TreeTags.FIND_IN_FILES_ENGINE).assertTextContains("by ripgrep")
            }.isSuccess }
        }

    @Test
    fun `a regular expression matches what the literal text does not`() =
        runComposeUiTest {
            write("src/Chips.txt", "bit\nbot\nbut\n")
            launchWithProject(project)
            openFindInFiles()
            type("b.t")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("No matches") }
            awaitTag(TreeTags.FIND_IN_FILES_REGEX).performClick()
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("3 matches in 1 file") }
            awaitTag(TreeTags.findInFilesItem("src/Chips.txt", 0))
        }

    @Test
    fun `a broken regular expression shows its error, and the search recovers`() =
        runComposeUiTest {
            write("src/Chips.txt", "bit\nbot\nneedle\n")
            launchWithProject(project)
            openFindInFiles()
            awaitTag(TreeTags.FIND_IN_FILES_REGEX).performClick()
            type("needle(")
            awaitTag(TreeTags.FIND_IN_FILES_ERROR)
            // A valid expression again: the results come back, the error line is gone.
            clearQuery()
            type("b.t")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 1 file") }
            awaitTag(TreeTags.findInFilesItem("src/Chips.txt", 0))
            awaitNoTag(TreeTags.FIND_IN_FILES_ERROR)
        }

    @Test
    fun `a file glob limits the search to the files it selects`() =
        runComposeUiTest {
            write("src/One.kt", "needle\n")
            write("src/Two.md", "needle\n")
            launchWithProject(project)
            openFindInFiles()
            type("needle")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files") }
            awaitTag(TreeTags.FIND_IN_FILES_GLOB_BUTTON).performClick()
            awaitTag(TreeTags.FIND_IN_FILES_GLOB_INPUT).performTextInput("*.kt")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("1 match in 1 file") }
            awaitTag(TreeTags.findInFilesItem("src/One.kt", 0))
        }

    @Test
    fun `ignored files are searched only when the option is on`() =
        runComposeUiTest {
            write("src/One.kt", "needle\n")
            write("build/out.txt", "needle\n")
            write(".gitignore", "build/\n")
            launchWithProject(project)
            openFindInFiles()
            type("needle")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("1 match in 1 file") }
            awaitTag(TreeTags.FIND_IN_FILES_INCLUDE_IGNORED).performClick()
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files") }
            awaitTag(TreeTags.findInFilesItem("build/out.txt", 0))
        }

    @Test
    fun `a scope searches the directory of the chip, the whole project when it is back`() =
        runComposeUiTest {
            write("src/One.kt", "needle here\n")
            write("docs/Two.md", "needle there\n")
            launchWithProject(project)
            openFindInFiles()
            type("needle")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files") }
            awaitTag(TreeTags.FIND_IN_FILES_SCOPE_BUTTON).performClick()
            awaitTag(TreeTags.FIND_IN_FILES_SCOPE_MENU)
            awaitTag(TreeTags.findInFilesScopeRow("src")).performClick()
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("1 match in 1 file") }
            awaitNoTag(TreeTags.findInFilesItem("docs/Two.md", 0))
            // The whole project comes back with the root row of the menu (the ".").
            awaitTag(TreeTags.FIND_IN_FILES_SCOPE_BUTTON).performClick()
            awaitTag(TreeTags.FIND_IN_FILES_SCOPE_MENU)
            awaitTag(TreeTags.findInFilesScopeRow(".")).performClick()
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files") }
        }

    @Test
    fun `without ripgrep, Find in Files still searches, with the built-in engine its results name`() =
        runComposeUiTest {
            val without =
                RipgrepSettings(executable = Files.createTempDirectory("no-rg").resolve("rg").toString())
            launchWithProject(project, ripgrep = without)
            openFindInFiles()
            type("Hello")
            countIs { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files") }
            awaitCondition { runCatching {
                onNodeWithTag(TreeTags.FIND_IN_FILES_ENGINE).assertTextContains("by the built-in engine")
            }.isSuccess }
        }
}
