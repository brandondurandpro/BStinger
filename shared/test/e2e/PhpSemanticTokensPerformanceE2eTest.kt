package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test

private const val FUNCTIONS = 2_000
private const val LINES_PER_FUNCTION = 4

// `function fN(int $value)`, `{`, `    return $value;`, `}`: the parameter in the body.
private const val BODY_OFFSET = 2
private const val PARAMETER_COLUMN = 11

/** Semantic tokens of a PHP file of 2,000 functions (12,000 tokens): arriving, typing above them, jumping. */
@OptIn(ExperimentalTestApi::class)
class PhpSemanticTokensPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val functions =
                (1..FUNCTIONS).flatMap { listOf("function f$it(int \$value)", "{", "    return \$value;", "}") }
            file("src/big.php", (listOf("<?php") + functions + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `the tokens of 2,000 functions color the code, follow typing and scroll quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/big.php")
            awaitServer(app) { it.isRunning() }
            val firstBody = 1 + BODY_OFFSET
            measure("semantic tokens of $FUNCTIONS functions", budgetMillis = 20_000) {
                awaitCondition(SERVER_START_MS) {
                    styleAt(firstBody, PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter
                }
                renderFrame()
            }
            clickAt(0, 2)
            measure("a line typed above the tokens", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.MoveEnd) }
                editorKeys { pressKey(Key.Enter) }
                awaitCondition { styleAt(firstBody + 1, PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter }
                renderFrame()
            }
            val lastBody = 1 + (FUNCTIONS - 1) * LINES_PER_FUNCTION + BODY_OFFSET + 1
            measure("jump to the end of the file", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
                awaitCondition { styleAt(lastBody, PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter }
                renderFrame()
            }
        }
}
