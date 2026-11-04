package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
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
import org.example.kotlineditor.ui.LanguageServerTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The files the IDE or another program creates, writes, renames and deletes, told to a PHP language server that
 * indexes the disk once (`workspace/didChangeWatchedFiles`, `didSave`): Go to Symbol follows them.
 */
@OptIn(ExperimentalTestApi::class)
class PhpDiskChangesE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers("--index").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    // Opens index.php, its server running and its index built.
    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        assertFound("Shy", "src/Shy.php")
        return app
    }

    private fun ComposeUiTest.searchSymbol(query: String) {
        val shown = onAllNodes(hasTestTag(TreeTags.GOTO_SYMBOL_INPUT)).fetchSemanticsNodes().isNotEmpty()
        if (!shown) {
            editorKeys {
                withKeyDown(
                    Key.CtrlLeft,
                ) { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
            }
        }
        awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextClearance()
        onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput(query)
    }

    private fun ComposeUiTest.closeSymbols() {
        onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performKeyInput { pressKey(Key.Escape) }
        awaitNoTag(TreeTags.GOTO_SYMBOL)
    }

    private fun ComposeUiTest.assertFound(name: String, path: String) {
        searchSymbol(name)
        awaitTag(TreeTags.gotoSymbolItem(name, path), timeout = SERVER_START_MS)
        closeSymbols()
    }

    // Searches [name], waits for the results to show [witness] (name, path), then checks [path] is not listed.
    private fun ComposeUiTest.assertNotFound(name: String, path: String, witness: Pair<String, String>) {
        searchSymbol(name)
        awaitTag(TreeTags.gotoSymbolItem(witness.first, witness.second), timeout = SERVER_START_MS)
        awaitNoTag(TreeTags.gotoSymbolItem(name, path))
        closeSymbols()
    }

    // The text of [path] on disk; empty while another process holds it (Windows refuses to share a file being written).
    private fun diskText(path: String): String =
        try {
            project.root
                .resolve(path)
                .toFile()
                .readText()
        } catch (_: java.io.IOException) {
            ""
        }

    private fun ComposeUiTest.treeAction(row: String, action: String) {
        rightClickOn(TreeTags.treeRow(row))
        awaitTag(action).performClick()
        awaitTag(AppTags.FILE_DIALOG)
    }

    @Test
    fun `a PHP file created and saved in the IDE is found by Go to Symbol once its tab is closed`() =
        runComposeUiTest {
            open()
            treeAction("src", TreeTags.MENU_NEW_FILE)
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Added.php")
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitTag(EditorTags.tab("src/Added.php"))
            onNodeWithTag(EditorTags.EDITOR).performClick()
            typeCode(listOf("<?php", "function addedLater() {}", "").joinToString("\n"))
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            awaitCondition {
                "addedLater" in diskText("src/Added.php")
            }
            // Closed, the file is known only from the index.
            onNodeWithTag(EditorTags.tabClose("src/Added.php"), useUnmergedTree = true).performClick()
            awaitNoTag(EditorTags.tab("src/Added.php"))
            assertFound("addedLater", "src/Added.php")
        }

    @Test
    fun `a PHP file deleted from the tree is no longer found by Go to Symbol`() =
        runComposeUiTest {
            project.file("src/ShyToo.php", listOf("<?php", "class ShyToo", "{", "}", "").joinToString("\n"))
            open()
            treeAction("src/Shy.php", TreeTags.MENU_DELETE)
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitNoTag(TreeTags.treeRow("src/Shy.php"))
            assertNotFound("Shy", "src/Shy.php", witness = "ShyToo" to "src/ShyToo.php")
        }

    @Test
    fun `a PHP file renamed in the tree is found at its new path`() =
        runComposeUiTest {
            project.file("src/ShyToo.php", listOf("<?php", "class ShyToo", "{", "}", "").joinToString("\n"))
            open()
            treeAction("src/Shy.php", TreeTags.MENU_RENAME)
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Timid.php")
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitTag(TreeTags.treeRow("src/Timid.php"))
            // The server renames the class with its file (willRenameFiles), then indexes the new path.
            assertFound("Timid", "src/Timid.php")
            assertNotFound("Shy", "src/Shy.php", witness = "ShyToo" to "src/ShyToo.php")
        }

    @Test
    fun `a deleted directory takes its PHP files out of Go to Symbol`() =
        runComposeUiTest {
            project.file("src/legacy/Old.php", listOf("<?php", "class OldThing", "{", "}", "").joinToString("\n"))
            project.file("src/OldThingKept.php", listOf("<?php", "class OldThingKept", "{", "}", "").joinToString("\n"))
            open()
            assertFound("OldThing", "src/legacy/Old.php")
            treeAction("src/legacy", TreeTags.MENU_DELETE)
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitNoTag(TreeTags.treeRow("src/legacy"))
            assertNotFound("OldThing", "src/legacy/Old.php", witness = "OldThingKept" to "src/OldThingKept.php")
        }

    @Test
    fun `saving an open PHP file tells its language server`() =
        runComposeUiTest {
            val app = open()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            typeCode("// edited\n")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            awaitCondition {
                "// edited" in diskText("src/index.php")
            }
            val server = awaitServer(app) { it.isRunning() }
            awaitTag(LanguageServerTags.statusLanguageServer(server.id)).performClick()
            awaitTag(LanguageServerTags.languageServerLog(server.id)).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS_LOG)
            awaitCondition { "saved file:" in textOf(LanguageServerTags.LANGUAGE_SERVERS_LOG) }
            awaitCondition { "index.php" in textOf(LanguageServerTags.LANGUAGE_SERVERS_LOG) }
        }

    @Test
    fun `a PHP file created by another program is found by Go to Symbol`() =
        runComposeUiTest {
            open()
            project.file("src/Outside.php", listOf("<?php", "class Outside", "{", "}", "").joinToString("\n"))
            assertFound("Outside", "src/Outside.php")
        }

    @Test
    fun `a PHP file deleted by another program is no longer found by Go to Symbol`() =
        runComposeUiTest {
            project.file("src/ShyToo.php", listOf("<?php", "class ShyToo", "{", "}", "").joinToString("\n"))
            open()
            project.root
                .resolve("src/Shy.php")
                .toFile()
                .delete()
            assertNotFound("Shy", "src/Shy.php", witness = "ShyToo" to "src/ShyToo.php")
        }
}
