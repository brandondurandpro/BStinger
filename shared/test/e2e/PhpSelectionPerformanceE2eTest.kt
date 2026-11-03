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
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val METHODS = 3_000
private const val LINES_PER_METHOD = 4

// Method 2,000: its `return $a;` line, the caret on `$a`.
private const val TARGET = 2_000
private const val HEADER_LINES = 3
private const val TARGET_DECLARATION = HEADER_LINES + (TARGET - 1) * LINES_PER_METHOD
private const val TARGET_RETURN = TARGET_DECLARATION + 2
private const val RETURN_INDENT = 8
private const val VARIABLE_COLUMN = 15
private const val CLASS_END = HEADER_LINES + METHODS * LINES_PER_METHOD

/** Ctrl+W in a PHP class of 3,000 methods, its ranges from the language server. */
@OptIn(ExperimentalTestApi::class)
class PhpSelectionPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val methods =
                (1..METHODS).flatMap {
                    listOf("    public function m$it(int \$a): int", "    {", "        return \$a;", "    }")
                }
            file("src/Big.php", (listOf("<?php", "class Big", "{") + methods + "}" + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/Big.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        // Ctrl+G to the variable of method 2,000.
        editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
        awaitTag(AppTags.GOTO_LINE_INPUT).performTextClearance()
        onNodeWithTag(AppTags.GOTO_LINE_INPUT).performTextInput("${TARGET_RETURN + 1}:${VARIABLE_COLUMN + 1}")
        onNodeWithTag(AppTags.GOTO_LINE_INPUT).performKeyInput { pressKey(Key.Enter) }
        awaitCondition {
            app.project.editor.activeTab
                ?.document
                ?.caret == TextPos(TARGET_RETURN, VARIABLE_COLUMN)
        }
        return app
    }

    @Test
    fun `Ctrl+W asks the server once, then grows to the class of 3,000 methods and the file without waiting`() =
        runComposeUiTest {
            val app = open()
            val variable = span(TARGET_RETURN, VARIABLE_COLUMN, TARGET_RETURN, VARIABLE_COLUMN + 2)
            measure("first Ctrl+W in a class of $METHODS PHP methods", budgetMillis = 3_000) {
                extendSelection()
                awaitCondition(SERVER_START_MS) { app.selection() == variable }
                renderFrame()
            }
            val steps =
                listOf(
                    span(TARGET_RETURN, RETURN_INDENT, TARGET_RETURN, RETURN_INDENT + 10),
                    span(TARGET_DECLARATION, 4, TARGET_DECLARATION + 3, 5),
                    span(1, 0, CLASS_END, 1),
                    span(0, 0, CLASS_END + 1, 0),
                )
            // The other steps come from the same answer: no round trip, each one a frame.
            measure("4 more Ctrl+W up to the whole file", budgetMillis = 2_000) {
                steps.forEach { step ->
                    extendSelection()
                    awaitCondition { app.selection() == step }
                    renderFrame()
                }
            }
            measure("4 Ctrl+Shift+W back to the variable", budgetMillis = 2_000) {
                repeat(steps.size) {
                    shrinkSelection()
                    renderFrame()
                }
                awaitCondition { app.selection() == variable }
            }
        }
}
