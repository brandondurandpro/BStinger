package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Directory names of 40 characters: seven of them take a path past the 260 characters of old Windows programs.
private const val LONG_NAME = "a-directory-name-of-forty-characters-xx"
private const val LONG_DEPTH = 7

/**
 * What the disks of Windows and macOS do that Linux does not: names that differ only by case are one file, some
 * names are devices, paths may be longer than 260 characters, and a watched directory must stay free to move.
 */
@OptIn(ExperimentalTestApi::class)
class PlatformFilesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // The names in the directory of [relative] as the disk spells them (on Windows and macOS, `Files.exists` answers
    // for any case).
    private fun namesBeside(relative: String): List<String> {
        val directory = project.root.resolve(relative).parent
        return Files.list(directory).use { paths -> paths.map { it.name }.toList() }
    }

    private fun ComposeUiTest.renameInTree(row: String, newName: String) {
        rightClickOn(TreeTags.treeRow(row))
        awaitTag(TreeTags.MENU_RENAME).performClick()
        awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
        onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput(newName)
        onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
    }

    // Whether the dialog shows an error that names [name].
    private fun ComposeUiTest.hasErrorAbout(name: String): Boolean =
        runCatching { onNodeWithTag(AppTags.FILE_DIALOG_ERROR).assertTextContains(name, substring = true) }.isSuccess

    @Test
    fun `renaming a file only by the case of its name renames it on disk`() =
        runComposeUiTest {
            launchWithProject(project)
            renameInTree("README.md", "Readme.md")
            awaitTag(TreeTags.treeRow("Readme.md"))
            awaitNoTag(TreeTags.treeRow("README.md"))
            awaitNoTag(AppTags.FILE_DIALOG)
            val names = namesBeside("README.md")
            assertTrue("Readme.md" in names, "$names")
            assertFalse("README.md" in names, "$names")
        }

    @Test
    fun `a file renamed only by case by another program shows its new name`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(TreeTags.treeRow("README.md"))
            // Through another name: a disk that ignores case may take a direct move for no move at all.
            val moved = Files.move(project.root.resolve("README.md"), project.root.resolve("readme.tmp"))
            Files.move(moved, project.root.resolve("Readme.md"))
            awaitTag(TreeTags.treeRow("Readme.md"))
            awaitNoTag(TreeTags.treeRow("README.md"))
        }

    @Test
    fun `a directory of the project can be renamed and deleted by another program while it is watched`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(TreeTags.treeChevron("docs")).performClick()
            awaitTag(TreeTags.treeRow("docs/guide.md"))
            // Windows refuses to move a directory a program holds open: the watch must not hold the ones it watches.
            val notes = Files.move(project.root.resolve("docs"), project.root.resolve("notes"))
            awaitTag(TreeTags.treeRow("notes"))
            awaitNoTag(TreeTags.treeRow("docs/guide.md"))
            notes.toFile().deleteRecursively()
            awaitNoTag(TreeTags.treeRow("notes"))
            assertFalse(Files.exists(notes))
        }

    @Test
    fun `a file whose path is longer than 260 characters shows, opens and saves`() =
        runComposeUiTest {
            val directory = List(LONG_DEPTH) { "$LONG_NAME$it" }.joinToString("/")
            val relative = "deep/$directory/long.txt"
            val app = launchWithProject(project)
            project.file(relative, "first\n")
            assertTrue(
                project.root
                    .resolve(relative)
                    .toString()
                    .length > 260,
            )
            awaitTag(TreeTags.treeRow("deep"))
            openFromTree(relative)
            onNodeWithTag(EditorTags.EDITOR).performClick()
            typeCode("x")
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            val document =
                app.project.editor.activeTab
                    ?.document
            awaitCondition { document?.isModified == false || document?.saveError != null }
            assertNull(document?.saveError)
            // Where the click put the caret does not matter: the typed text is on disk, beside the old one.
            val saved = project.read(relative)
            assertTrue("x" in saved && "first" in saved, saved)
        }

    @Test
    fun `a name Windows keeps for a device, forbids or would change is refused there and nothing is created`() =
        runComposeUiTest {
            if (WINDOWS) {
                launchWithProject(project)
                rightClickOn(TreeTags.treeRow("docs"))
                awaitTag(TreeTags.MENU_NEW_FILE).performClick()
                // The user corrects the name in the dialog until the disk can take it.
                for (name in listOf("con", "aux.txt", "nul.tar.gz", "COM1", "notes.", "what?.txt")) {
                    awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
                    onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput(name)
                    onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
                    awaitCondition { hasErrorAbout(name) }
                }
                onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
                onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("notes.txt")
                onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
                awaitTag(TreeTags.treeRow("docs/notes.txt"))
                assertEquals(setOf("guide.md", "notes.txt"), namesBeside("docs/guide.md").toSet())
            }
        }
}
