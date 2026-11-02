package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test

/** Semantic tokens when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpSemanticTokensChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/shapes.php", SHAPES_PHP) }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.parameterColored(line: Int = Shapes.PARAMETER_LINE): Boolean =
        styleAt(line, Shapes.PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/shapes.php")
        awaitServer(app) { it.isRunning() }
        awaitCondition(SERVER_START_MS) { parameterColored() }
        return app
    }

    // A line inserted after the opening tag.
    private fun ComposeUiTest.insertLineAtTop() {
        clickAt(0, 2)
        editorKeys { pressKey(Key.MoveEnd) }
        editorKeys { pressKey(Key.Enter) }
    }

    @Test
    fun `a line inserted while the network is down keeps the colors on their words, the server confirms them after`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            insertLineAtTop()
            // Moved with the text, without the server.
            awaitCondition { parameterColored(Shapes.PARAMETER_LINE + 1) }
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { parameterColored(Shapes.PARAMETER_LINE + 1) }
            awaitCondition { styleAt(Shapes.CLASS_LINE + 1, Shapes.CLASS_COLUMN)?.color == IdeColors.SemanticType }
        }

    @Test
    fun `when the link goes silent the colors stay, and follow the text once it is back`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            awaitReconnecting()
            insertLineAtTop()
            awaitCondition { parameterColored(Shapes.PARAMETER_LINE + 1) }
            app.network.blackHole = false
            awaitReconnected()
            insertLineAtTop()
            awaitCondition(SERVER_START_MS) { parameterColored(Shapes.PARAMETER_LINE + 2) }
        }

    @Test
    fun `on a slow link the colors of the latest text win over late answers`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = SLOW_MS
            insertLineAtTop()
            insertLineAtTop()
            awaitCondition(SERVER_START_MS) { parameterColored(Shapes.PARAMETER_LINE + 2) }
            // The answer for the text with one line inserted must not color the line above.
            repeat(SETTLE_CHECKS) {
                awaitCondition {
                    !parameterColored(
                        Shapes.PARAMETER_LINE + 1,
                    ) &&
                        parameterColored(Shapes.PARAMETER_LINE + 2)
                }
                waitForIdle()
            }
        }

    @Test
    fun `after the server restarts the colors come back with the next edit`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            insertLineAtTop()
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            awaitCondition(SERVER_START_MS) { parameterColored(Shapes.PARAMETER_LINE + 1) }
            awaitCondition { styleAt(Shapes.UNIT_LINE + 1, Shapes.UNIT_COLUMN)?.color == IdeColors.FunctionDeclaration }
        }
}

private const val SLOW_MS = 400L
private const val SETTLE_CHECKS = 5
