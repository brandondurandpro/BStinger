package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.LanguageServerTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GREETER_FILE = "src/Greeter.php"
private const val WELCOMER_FILE = "src/Welcomer.php"
private const val INDEX_FILE = "src/index.php"

/**
 * File operations told to the PHP language server (`workspace/willRenameFiles`, `didCreateFiles`...): the class
 * named after a file renamed in the tree is renamed with it, in every file, before the file moves.
 */
@OptIn(ExperimentalTestApi::class)
class PhpFileOperationsE2eTest {
    private val project = codeInsightProject()
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun disk(path: String): String =
        project.root
            .resolve(path)
            .toFile()
            .readText()

    private fun exists(path: String): Boolean =
        project.root
            .resolve(path)
            .toFile()
            .exists()

    private fun E2eApp.openText(path: String): String? =
        project.editor.allTabs
            .firstOrNull { it.relativePath == path && !it.isDiff }
            ?.document
            ?.lines
            ?.joinToString("\n")

    // Opens [path], its server running.
    private fun ComposeUiTest.open(path: String, vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.renameInTree(row: String, newName: String) {
        rightClickOn(TreeTags.treeRow(row))
        awaitTag(TreeTags.MENU_RENAME).performClick()
        awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
        onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput(newName)
        onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
    }

    private fun ComposeUiTest.awaitLog(text: String, app: E2eApp) {
        val server = awaitServer(app) { it.isRunning() }
        if (!hasTag(LanguageServerTags.LANGUAGE_SERVERS_LOG)) {
            awaitTag(LanguageServerTags.statusLanguageServer(server.id)).performClick()
            awaitTag(LanguageServerTags.languageServerLog(server.id)).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS_LOG)
        }
        awaitCondition(timeout = SERVER_START_MS) { text in textOf(LanguageServerTags.LANGUAGE_SERVERS_LOG) }
    }

    private fun ComposeUiTest.hasTag(tag: String): Boolean =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
        ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `renaming a class file in the tree renames the class in the open files and on disk`() =
        runComposeUiTest {
            val app = open(INDEX_FILE)
            renameInTree(GREETER_FILE, "Welcomer.php")
            awaitTag(TreeTags.treeRow(WELCOMER_FILE))
            awaitNoTag(AppTags.FILE_DIALOG)
            awaitCondition { "new Welcomer();" in app.openText(INDEX_FILE).orEmpty() }
            assertTrue("class Welcomer implements Polite" in disk(WELCOMER_FILE))
            assertFalse(exists(GREETER_FILE))
            assertFalse("Greeter" in app.openText(INDEX_FILE).orEmpty())
        }

    @Test
    fun `the renamed file open in the editor is reopened with its class renamed, and closed files are rewritten`() =
        runComposeUiTest {
            val app = open(GREETER_FILE)
            renameInTree(GREETER_FILE, "Welcomer.php")
            awaitTag(EditorTags.tab(WELCOMER_FILE))
            awaitNoTag(EditorTags.tab(GREETER_FILE))
            awaitCondition { "class Welcomer implements Polite" in app.openText(WELCOMER_FILE).orEmpty() }
            assertTrue("class Welcomer implements Polite" in disk(WELCOMER_FILE))
            assertTrue("\$greeter = new Welcomer();" in disk(INDEX_FILE))
        }

    @Test
    fun `renaming a file that names no class, or no PHP file, changes no code`() =
        runComposeUiTest {
            open(GREETER_FILE)
            val index = disk(INDEX_FILE)
            val greeter = disk(GREETER_FILE)
            renameInTree(INDEX_FILE, "main.php")
            awaitTag(TreeTags.treeRow("src/main.php"))
            renameInTree("README.md", "NOTES.md")
            awaitTag(TreeTags.treeRow("NOTES.md"))
            assertEquals(index, disk("src/main.php"))
            assertEquals(greeter, disk(GREETER_FILE))
            assertEquals("# Greetings\n", disk("NOTES.md"))
        }

    @Test
    fun `a rename onto an existing file fails without touching the code`() =
        runComposeUiTest {
            val app = open(INDEX_FILE)
            val shy = disk("src/Shy.php")
            renameInTree(GREETER_FILE, "Shy.php")
            awaitTag(AppTags.FILE_DIALOG_ERROR)
            assertTrue(exists(GREETER_FILE))
            assertEquals(shy, disk("src/Shy.php"))
            assertTrue("new Greeter();" in app.openText(INDEX_FILE).orEmpty())
            assertTrue("class Greeter implements Polite" in disk(GREETER_FILE))
        }

    @Test
    fun `cancelling the rename dialog changes nothing`() =
        runComposeUiTest {
            val app = open(INDEX_FILE)
            rightClickOn(TreeTags.treeRow(GREETER_FILE))
            awaitTag(TreeTags.MENU_RENAME).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Welcomer.php")
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue(exists(GREETER_FILE))
            assertFalse(exists(WELCOMER_FILE))
            assertTrue("new Greeter();" in app.openText(INDEX_FILE).orEmpty())
        }

    @Test
    fun `without file operations in the server a rename moves the file only`() =
        runComposeUiTest {
            val app = open(INDEX_FILE, "--without", "fileOperations")
            renameInTree(GREETER_FILE, "Welcomer.php")
            awaitTag(TreeTags.treeRow(WELCOMER_FILE))
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue("class Greeter implements Polite" in disk(WELCOMER_FILE))
            assertTrue("new Greeter();" in app.openText(INDEX_FILE).orEmpty())
        }

    @Test
    fun `created, renamed and deleted PHP files are told to the server`() =
        runComposeUiTest {
            val app = open(INDEX_FILE)
            rightClickOn(TreeTags.treeRow("src"))
            awaitTag(TreeTags.MENU_NEW_FILE).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Added.php")
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitTag(EditorTags.tab("src/Added.php"))
            awaitLog("didCreateFiles", app)
            renameInTree("src/Added.php", "Renamed.php")
            awaitTag(TreeTags.treeRow("src/Renamed.php"))
            awaitLog("didRenameFiles", app)
            rightClickOn(TreeTags.treeRow("src/Renamed.php"))
            awaitTag(TreeTags.MENU_DELETE).performClick()
            awaitTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitNoTag(TreeTags.treeRow("src/Renamed.php"))
            awaitLog("didDeleteFiles", app)
            awaitLog("Renamed.php", app)
        }
}
