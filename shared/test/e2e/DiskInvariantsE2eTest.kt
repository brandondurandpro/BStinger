package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the IDE writes changes only what the user changed: an edit undone and saved again, or a file only opened,
 * leaves every file of the project byte for byte, with its permissions and links, and Git as they were. Run on the
 * files real projects have ([withUnusualFiles]): line endings, encodings, permissions, links, names. What it
 * deletes is only what the user deleted: never what a link points to.
 */
@OptIn(ExperimentalTestApi::class)
class DiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }
    private lateinit var app: E2eApp

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
    }

    private fun ComposeUiTest.save() {
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
    }

    private fun bytesOf(path: String): ByteArray = Files.readAllBytes(project.root.resolve(path))

    // Deletes [path] from the project view, as a user does, and waits until the tree no longer shows it.
    private fun ComposeUiTest.deleteFromTree(path: String) {
        rightClickOn(TreeTags.treeRow(path))
        awaitTag(TreeTags.MENU_DELETE).performClick()
        awaitTag(AppTags.FILE_DIALOG)
        onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
        awaitNoTag(TreeTags.treeRow(path))
    }

    private fun existsOnDisk(path: String): Boolean =
        Files.exists(project.root.resolve(path), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `deleting a directory holding a link to a directory outside the project leaves what the link points to`() =
        runComposeUiTest {
            // Windows: no link without a privilege, nothing to delete.
            if (project.hasOutsideLink()) {
                val before = DiskSnapshot.take(project)
                launchWithProject(project)
                deleteFromTree("linked")
                awaitCondition { !existsOnDisk("linked") }
                before.assertUnchanged(project, except = setOf(OUTSIDE_LINK))
            }
        }

    @Test
    fun `deleting a link to a directory outside the project deletes the link, not what it points to`() =
        runComposeUiTest {
            if (project.hasOutsideLink()) {
                val before = DiskSnapshot.take(project)
                launchWithProject(project)
                awaitTag(TreeTags.treeChevron("linked")).performClick()
                awaitTag(TreeTags.treeRow(OUTSIDE_LINK))
                deleteFromTree(OUTSIDE_LINK)
                awaitCondition { !existsOnDisk(OUTSIDE_LINK) }
                assertTrue(existsOnDisk("linked"))
                before.assertUnchanged(project, except = setOf(OUTSIDE_LINK))
            }
        }

    // Types, saves, undoes and saves again [file]; the problem, if the disk is not as before.
    private fun ComposeUiTest.roundTrip(file: UnusualFile, before: DiskSnapshot): String? {
        val original = bytesOf(file.path)
        openFromTree(file.path)
        onNodeWithTag(EditorTags.EDITOR).performClick()
        typeCode("x")
        save()
        awaitCondition { !bytesOf(file.path).contentEquals(original) }
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        save()
        awaitCondition {
            app.project.editor.activeTab
                ?.document
                ?.isModified == false
        }
        waitForIdle()
        val problem = runCatching { before.assertUnchanged(project) }.exceptionOrNull()?.message
        return problem?.let { "${file.path} (${file.unusual}): $it" }
    }

    @Test
    fun `an edit undone and saved again leaves the disk and Git as they were`() =
        runComposeUiTest {
            app = launchWithProject(project)
            val problems = ArrayList<String>()
            for (file in project.unusualFiles()) {
                val before = DiskSnapshot.take(project)
                val problem = roundTrip(file, before)
                // The next file starts from the disk as it is now, so that one problem is reported once.
                if (problem != null) problems += problem
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
        }

    @Test
    fun `opening every file changes nothing on disk`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchWithProject(project)
            for (file in project.unusualFiles()) openFromTree(file.path)
            waitForIdle()
            before.assertUnchanged(project)
        }

    // Line endings are the point: a raw string cannot hold a CR.
    @Suppress("StringShouldBeRawString")
    @Test
    fun `a file with mixed line endings is saved with CRLF once edited, and nothing else changes`() =
        runComposeUiTest {
            project.file("unusual/mixed.txt", "first\r\nsecond\nthird\r\n")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "mixed")
            val before = DiskSnapshot.take(project)
            app = launchWithProject(project)
            openFromTree("unusual/mixed.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            typeCode("x")
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            save()
            awaitCondition { project.read("unusual/mixed.txt") == "first\r\nsecond\r\nthird\r\n" }
            before.assertUnchanged(project, except = setOf("unusual/mixed.txt"))
        }

    @Test
    fun `a read-only file the server may not write is not saved, and the editor says why`() =
        runComposeUiTest {
            val file = project.file("unusual/read-only.txt", "first\n")
            file.toFile().setWritable(false)
            // Root writes any file (the Linux containers of `./linux-vm`): there the server may save it.
            if (!Files.isWritable(file)) assertReadOnlyRefused(file)
            file.toFile().setWritable(true)
        }

    private fun ComposeUiTest.assertReadOnlyRefused(file: Path) {
        project.git("add", "-A")
        project.git("commit", "-q", "-m", "read-only")
        val before = DiskSnapshot.take(project)
        launchWithProject(project)
        openFromTree(project.root.relativize(file).joinToString("/"))
        onNodeWithTag(EditorTags.EDITOR).performClick()
        typeCode("x")
        save()
        awaitTag(EditorTags.SAVE_ERROR).assertTextContains("read-only", substring = true)
        before.assertUnchanged(project)
    }

    @Test
    fun `a character the encoding of a file cannot hold fails its save, which the editor says`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchWithProject(project)
            openFromTree("unusual/latin1.php")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            typeCode("\u20ac")
            save()
            awaitTag(EditorTags.SAVE_ERROR).assertTextContains("ISO-8859-1", substring = true)
            before.assertUnchanged(project)
        }
}
