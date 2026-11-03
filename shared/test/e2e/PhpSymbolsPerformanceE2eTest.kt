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
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val FUNCTIONS = 3_000
private const val FILES = 1_000
private const val FUNCTIONS_PER_FILE = 5

// Rows of a popup list composed at once: it is virtualized, not one row per declaration.
private const val MAX_COMPOSED_ROWS = 100

/** File Structure of a PHP file of thousands of functions, Go to Symbol in a project of a thousand PHP files. */
@OptIn(ExperimentalTestApi::class)
class PhpSymbolsPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val functions = (1..FUNCTIONS).map { "function f$it(int \$a): int { return \$a; }" }
            file("src/big.php", (listOf("<?php") + functions + "").joinToString("\n"))
            repeat(FILES) { index ->
                val declared = (1..FUNCTIONS_PER_FILE).map { "function file${index}_g$it() {}" }
                file("src/many/File$index.php", (listOf("<?php") + declared + "").joinToString("\n"))
            }
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/big.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.composedRows(prefix: String) =
        onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes().size

    @Test
    fun `File Structure of a file of 3,000 functions opens quickly and composes only the visible rows`() =
        runComposeUiTest {
            val app = open()
            measure("File Structure of $FUNCTIONS PHP functions", budgetMillis = 5_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F12) } }
                awaitTag(TreeTags.structureItem("f1", 1), timeout = SERVER_START_MS)
                awaitNoTag(TreeTags.STRUCTURE_LOADING, timeout = SERVER_START_MS)
                renderFrame()
            }
            val rows = composedRows("structure.item:")
            assertTrue(rows <= MAX_COMPOSED_ROWS, "$rows rows composed")
            measure("filter $FUNCTIONS declarations", budgetMillis = 2_000) {
                onNodeWithTag(TreeTags.STRUCTURE_INPUT).performTextInput("f2999")
                awaitTag(TreeTags.structureItem("f2999", 2999))
                renderFrame()
            }
            onNodeWithTag(TreeTags.STRUCTURE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(2999, "function ".length)
            }
        }

    @Test
    fun `Go to Symbol finds a function among those of a thousand files quickly`() =
        runComposeUiTest {
            open()
            editorKeys {
                withKeyDown(
                    Key.CtrlLeft,
                ) { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
            }
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT)
            measure("Go to Symbol in ${FILES * FUNCTIONS_PER_FILE} PHP functions", budgetMillis = 5_000) {
                onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("file999_g5")
                awaitTag(TreeTags.gotoSymbolItem("file999_g5", "src/many/File999.php"), timeout = SERVER_START_MS)
                renderFrame()
            }
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextClearance()
            awaitNoTag(TreeTags.gotoSymbolItem("file999_g5", "src/many/File999.php"))
            // Every function matches: the server's list is capped, then the best ones shown.
            measure("Go to Symbol listing ${FILES * FUNCTIONS_PER_FILE} matches", budgetMillis = 5_000) {
                onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("g")
                awaitCondition(SERVER_START_MS) { composedRows("gotoSymbol.item:") > 1 }
                renderFrame()
            }
            val rows = composedRows("gotoSymbol.item:")
            assertTrue(rows <= MAX_COMPOSED_ROWS, "$rows rows composed")
        }
}
