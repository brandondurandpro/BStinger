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

// As many functions without a docblock as the IDE resolves lenses for: one `Add docblock` lens each.
private const val FUNCTIONS = 1_000
private const val FIRST_FUNCTION_LINE = 1

/** Code lenses of a PHP file of 1,000 functions: resolved and shown, following typing, at the end of the file. */
@OptIn(ExperimentalTestApi::class)
class PhpCodeLensPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val functions = (1..FUNCTIONS).map { "function f$it(\$x) { return \$x; }" }
            file("src/many.php", (listOf("<?php") + functions + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `1,000 lenses are resolved, follow typing and are drawn at the end of the file`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/many.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            val insight = document?.let { app.project.insight.insightOf(it) }
            measure("$FUNCTIONS code lenses resolved by the server", budgetMillis = 20_000) {
                awaitCondition(SERVER_START_MS) { insight?.codeLenses?.size == FUNCTIONS }
                awaitTag(EditorTags.codeLens(FIRST_FUNCTION_LINE, 0), unmerged = true)
                renderFrame()
            }
            clickAt(0, 0)
            measure("a line typed above $FUNCTIONS lenses", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.MoveEnd) }
                editorKeys { pressKey(Key.Enter) }
                awaitTag(EditorTags.codeLens(FIRST_FUNCTION_LINE + 1, 0), unmerged = true)
                renderFrame()
            }
            // After the line inserted: the last function is one line further.
            val lastFunction = FIRST_FUNCTION_LINE + FUNCTIONS
            measure("jump to the end with $FUNCTIONS lenses", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
                awaitTag(EditorTags.codeLens(lastFunction, 0), unmerged = true)
                renderFrame()
            }
            assertEquals(FUNCTIONS, insight?.codeLenses?.size)
        }
}
