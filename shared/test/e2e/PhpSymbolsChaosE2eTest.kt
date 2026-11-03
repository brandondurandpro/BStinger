package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** File Structure and Go to Symbol in PHP when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpSymbolsChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(path: String): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
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

    private fun ComposeUiTest.searchSymbol(query: String) {
        onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextClearance()
        onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput(query)
    }

    private fun ComposeUiTest.closePopup(input: String, popup: String) {
        onNodeWithTag(input).performKeyInput { pressKey(Key.Escape) }
        awaitNoTag(popup)
    }

    @Test
    fun `Go to Symbol while the network is down lists nothing, then finds the symbol once it is back`() =
        runComposeUiTest {
            val app = open("src/index.php")
            app.network.goDown()
            awaitReconnecting()
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT)
            searchSymbol("greet")
            // Nothing pretends to be found, and the popup does not stay searching for ever.
            awaitNoTag(TreeTags.GOTO_SYMBOL_SEARCHING, timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"))
            app.network.comeBack()
            awaitReconnected()
            searchSymbol("gree")
            searchSymbol("greet")
            awaitTag(TreeTags.gotoSymbolItem("greet", "src/Greeter.php"), timeout = SERVER_START_MS)
        }

    @Test
    fun `on a slow link Go to Symbol shows the symbols of the last name typed`() =
        runComposeUiTest {
            val app = open("src/index.php")
            app.network.latencyMs = 400
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT)
            searchSymbol("Shy")
            searchSymbol("Polite")
            awaitTag(TreeTags.gotoSymbolItem("Polite", "src/Polite.php"), timeout = SERVER_START_MS)
            // The answer for the name typed before never replaces it.
            mainClock.advanceTimeBy(1_500)
            awaitNoTag(TreeTags.gotoSymbolItem("Shy", "src/Shy.php"))
            awaitTag(TreeTags.gotoSymbolItem("Polite", "src/Polite.php"))
        }

    @Test
    fun `on a slow link File Structure shows the declarations once they arrive`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            app.network.latencyMs = 400
            fileStructure()
            awaitTag(TreeTags.structureItem("greet", 13), timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.STRUCTURE_LOADING)
        }

    @Test
    fun `a link gone silent does not leave File Structure loading once it is back`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            app.network.blackHole = true
            fileStructure()
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            // The popup opened while the link was silent fills itself: nothing to reopen.
            awaitTag(TreeTags.structureItem("greet", 13), timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.STRUCTURE_LOADING)
            closePopup(TreeTags.STRUCTURE_INPUT, TreeTags.STRUCTURE)
        }

    @Test
    fun `after the IDE server restarts Go to Symbol and File Structure work again`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            val before = awaitServer(app) { it.isRunning() }
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() && it.pid != before.pid }
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT)
            searchSymbol("Polite")
            awaitTag(TreeTags.gotoSymbolItem("Polite", "src/Polite.php"), timeout = SERVER_START_MS)
            closePopup(TreeTags.GOTO_SYMBOL_INPUT, TreeTags.GOTO_SYMBOL)
            fileStructure()
            awaitTag(TreeTags.structureItem("greet", 13), timeout = SERVER_START_MS)
        }

    @Test
    fun `a file deleted on disk is no longer found by Go to Symbol`() =
        runComposeUiTest {
            project.file("src/ShyToo.php", listOf("<?php", "class ShyToo", "{", "}", "").joinToString("\n"))
            open("src/index.php")
            Files.delete(project.root.resolve("src/Shy.php"))
            gotoSymbol()
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT)
            searchSymbol("Shy")
            // The results are in once the file still there shows; the deleted one is not among them.
            awaitTag(TreeTags.gotoSymbolItem("ShyToo", "src/ShyToo.php"), timeout = SERVER_START_MS)
            awaitNoTag(TreeTags.gotoSymbolItem("Shy", "src/Shy.php"))
        }
}
