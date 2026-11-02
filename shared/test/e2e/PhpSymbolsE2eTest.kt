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
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * File Structure (Ctrl+F12) and Go to Symbol (Ctrl+Alt+Shift+N) in PHP, whose declarations come from the language
 * server (documentSymbol and workspace/symbol), through the UI.
 */
@OptIn(ExperimentalTestApi::class)
class PhpSymbolsE2eTest {
    private val project = codeInsightProject()
    private val created = ArrayList<FakePhpServers>()

    private fun servers(vararg options: String) = FakePhpServers(*options).chosen().also { created += it }

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    private fun E2eApp.activePath(): String? = project.editor.activeTab?.relativePath

    // Opens [path] with its language server running.
    private fun ComposeUiTest.open(path: String, vararg options: String): E2eApp {
        val app = launchWithProject(project, languageServers = servers(*options).settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.fileStructure() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F12) } }

    private fun ComposeUiTest.gotoSymbol() =
        editorKeys {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
        }

    @Test
    fun `File Structure lists the class and its methods, and a click goes to the method's name`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            fileStructure()
            awaitTag(TreeTags.structureItem("Greeter", 5), timeout = SERVER_START_MS)
            awaitTag(TreeTags.structureItem("greet", 13))
            awaitNoTag(TreeTags.STRUCTURE_LOADING)
            onNodeWithTag(TreeTags.structureItem("greet", 13)).performClick()
            awaitNoTag(TreeTags.STRUCTURE)
            awaitCondition { app.caret() == TextPos(13, 20) }
        }

    @Test
    fun `File Structure filters the declarations as you type, and Enter goes to the chosen one`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            fileStructure()
            awaitTag(TreeTags.structureItem("greet", 13), timeout = SERVER_START_MS)
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performTextInput("xyz")
            awaitNoTag(TreeTags.structureItem("greet", 13))
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performTextClearance()
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performTextInput("gre")
            awaitTag(TreeTags.structureItem("Greeter", 5))
            awaitTag(TreeTags.structureItem("greet", 13))
            // The first match is chosen: Down goes to the method.
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitCondition { app.caret() == TextPos(13, 20) }
        }

    @Test
    fun `File Structure of a file whose server lists no symbols shows nothing and does not stay loading`() =
        runComposeUiTest {
            open("src/Greeter.php", "--without", "documentSymbol")
            fileStructure()
            awaitTag(TreeTags.STRUCTURE)
            awaitNoTag(TreeTags.STRUCTURE_LOADING, timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.structureItem("Greeter", 5))
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.STRUCTURE)
        }

    @Test
    fun `Go to Symbol finds a method of a file not open, and Enter opens it on its name`() =
        runComposeUiTest {
            val app = open("src/index.php")
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("greet")
            awaitTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"), timeout = SERVER_START_MS)
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            awaitCondition { app.caret() == TextPos(13, 20) }
            // Back returns where Go to Symbol was used.
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } } }
            awaitCondition { app.activePath() == "src/index.php" }
        }

    @Test
    fun `Go to Symbol finds interfaces and classes, only those whose name matches`() =
        runComposeUiTest {
            open("src/index.php")
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("Polite")
            awaitTag(TreeTags.gotoSymbolItem("Polite", "src/Polite.php"), timeout = SERVER_START_MS)
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextClearance()
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("Shy")
            awaitTag(TreeTags.gotoSymbolItem("Shy", "src/Shy.php"), timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.gotoSymbolItem("Polite", "src/Polite.php"))
        }

    @Test
    fun `Go to Symbol with a name nothing declares lists nothing, and Escape closes it`() =
        runComposeUiTest {
            open("src/index.php")
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("greet")
            awaitTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"), timeout = SERVER_START_MS)
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("zzz")
            awaitNoTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"))
            awaitNoTag(TreeTags.GOTO_SYMBOL_SEARCHING)
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.GOTO_SYMBOL)
        }

    @Test
    fun `Go to Symbol with a server that does not search symbols lists nothing and does not stay searching`() =
        runComposeUiTest {
            // A Kotlin declaration, found without language server, shows when the search is done.
            project.file("src/Witness.kt", "fun greetWitness() {}\n")
            val app = open("src/index.php", "--without", "workspaceSymbol")
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("greet")
            awaitTag(TreeTags.gotoSymbolItem("greetWitness", "src/Witness.kt"), timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"))
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(TreeTags.GOTO_SYMBOL)
            assertEquals("src/index.php", app.activePath())
        }
}
