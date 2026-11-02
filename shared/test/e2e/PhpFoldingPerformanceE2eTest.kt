package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val FUNCTIONS = 3_000
private const val LINES_PER_FUNCTION = 4

/** Folding a PHP file of 3,000 functions, whose regions come from the language server. */
@OptIn(ExperimentalTestApi::class)
class PhpFoldingPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val functions = (1..FUNCTIONS).flatMap { listOf("function f$it()", "{", "    return $it;", "}") }
            file("src/big.php", (listOf("<?php") + functions + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `the 3,000 regions of the server arrive, fold all and unfold all quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/big.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            measure("$FUNCTIONS folding regions from the server", budgetMillis = 15_000) {
                awaitCondition(SERVER_START_MS) { document?.folding?.regions?.size == FUNCTIONS }
                awaitTag(EditorTags.foldMarker(1), unmerged = true)
                renderFrame()
            }
            measure("fold all $FUNCTIONS functions", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Minus) } } }
                awaitCondition { document?.folding?.rowCount() == FUNCTIONS + 2 }
                renderFrame()
            }
            // Each function is one row: the row of the last one is its declaration line.
            assertEquals(1 + (FUNCTIONS - 1) * LINES_PER_FUNCTION, document?.folding?.lineAt(FUNCTIONS))
            measure("unfold all", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Equals) } } }
                awaitCondition { document?.folding?.rowCount() == document?.lineCount }
                renderFrame()
            }
            assertEquals(FUNCTIONS, document?.folding?.regions?.size)
        }
}
