package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val PROBLEMS = 2_000

// Rows of the list composed at once: it is virtualized, not one row per problem.
private const val MAX_COMPOSED_ROWS = 40

/** The problems list of a PHP file of 2,000 problems. */
@OptIn(ExperimentalTestApi::class)
class ProblemsListPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val calls = (1..PROBLEMS).map { "missing$it();" }
            file("src/big.php", (listOf("<?php") + calls + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `the list of 2,000 problems opens quickly, composes only the visible rows and goes to the last one`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/big.php")
            awaitServer(app) { it.isRunning() }
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.PROBLEMS_ERRORS) == "$PROBLEMS" }
            measure("problems list of $PROBLEMS problems", budgetMillis = 3_000) {
                onNodeWithTag(EditorTags.PROBLEMS).performClick()
                awaitTag(EditorTags.problem(0))
                renderFrame()
            }
            val rows = onAllNodes(hasTestTagPrefix("problems.list:")).fetchSemanticsNodes().size
            assertTrue(rows <= MAX_COMPOSED_ROWS, "$rows rows composed")
            measure("50 moves down the list", budgetMillis = 3_000) {
                repeat(50) {
                    editorKeys { pressKey(Key.DirectionDown) }
                    renderFrame()
                }
            }
            measure("to the last problem, at the end of the file", budgetMillis = 3_000) {
                // Up from the 51st row: 50 more to the first, then the last one.
                repeat(51) { editorKeys { pressKey(Key.DirectionUp) } }
                editorKeys { pressKey(Key.Enter) }
                awaitCondition {
                    app.project.editor.activeTab
                        ?.document
                        ?.caret == TextPos(PROBLEMS, 0)
                }
                awaitTag(EditorTags.line(PROBLEMS))
                renderFrame()
            }
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            assertTrue(onAllNodes(hasTestTag(EditorTags.PROBLEMS_LIST)).fetchSemanticsNodes().isEmpty())
        }
}
