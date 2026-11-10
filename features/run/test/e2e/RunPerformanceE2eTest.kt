package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.RunTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// More lines than the output keeps, so that the dropping of the oldest is measured too.
private const val OUTPUT_LINES = 60_000
private const val KEPT_LINES = 50_000
private const val SUITES = 50
private const val TESTS_PER_SUITE = 100

// Rows composed at once in a window a few dozen rows high: far fewer than the thousands of lines or tests.
private const val MAX_COMPOSED_ROWS = 200

/** The Run tool window on a command that prints a lot, and on a test run with thousands of tests. */
@OptIn(ExperimentalTestApi::class)
class RunPerformanceE2eTest {
    private val project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.composedRows(prefix: String): Int =
        onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes().size

    @Test
    fun `sixty thousand lines of output are shown without composing them all`() =
        runComposeUiTest {
            project.file("out.txt", (1..OUTPUT_LINES).joinToString("\n") { "line $it of the build output" })
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Noisy", emit("out.txt"))
            measure("run.output $OUTPUT_LINES lines", budgetMillis = 8_000, budgetMegabytes = 400) {
                pressRun()
                awaitRunStatus(app, RunStatus.Succeeded)
                renderFrame()
            }
            val session = checkNotNull(app.project.run.session)
            assertEquals(KEPT_LINES, session.lines.size)
            // The notice of the JVM (one line, when JAVA_TOOL_OPTIONS is set) may be among the dropped lines.
            val expectedDropped = OUTPUT_LINES - KEPT_LINES
            assertTrue(session.droppedLines in expectedDropped..expectedDropped + 1, "dropped: ${session.droppedLines}")
            assertEquals("line $OUTPUT_LINES of the build output", session.lines.last())
            awaitTag(RunTags.DROPPED)
            assertTrue(composedRows("run.line:") in 1..MAX_COMPOSED_ROWS, "rows: ${composedRows("run.line:")}")
        }

    @Test
    fun `five thousand tests make a tree that scrolls without composing every row`() =
        runComposeUiTest {
            val lines =
                (1..SUITES).flatMap { suite ->
                    listOf("Started Suite$suite") +
                        (1..TESTS_PER_SUITE).flatMap { test ->
                            val name = "test$test()"
                            val result = if (test == 1) "Failed" else "Passed"
                            listOf("Started $name", "$result $name")
                        } +
                        "Completed Suite$suite"
                }
            project.file("out.txt", lines.joinToString("\n"))
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Many tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            val total = SUITES * TESTS_PER_SUITE
            measure("run.tests $total results", budgetMillis = 6_000, budgetMegabytes = 100) {
                pressRun()
                awaitRunStatus(app, RunStatus.Failed)
                awaitCondition {
                    app.project.run.session
                        ?.results
                        ?.sumOf { it.tests.size } == total
                }
                renderFrame()
            }
            val failed = SUITES
            awaitTag(RunTags.SUMMARY).assertTextEquals("${total - failed} passed, $failed failed, 0 skipped")
            // Every suite has a failure, so every suite is open: 5,050 rows, of which a few dozen are composed.
            val composed = composedRows("run.test:") + composedRows("run.suite:")
            assertTrue(composed in 1..MAX_COMPOSED_ROWS, "rows composed: $composed")
            measure("run.tests failed only", budgetMillis = 1_500, budgetMegabytes = 50) {
                onNodeWithTag(RunTags.ONLY_FAILED).performClick()
                awaitNoTag(RunTags.test("Suite1", "test2()"))
                renderFrame()
            }
            measure("run.tests close a suite", budgetMillis = 1_500, budgetMegabytes = 50) {
                onNodeWithTag(RunTags.suite("Suite1")).performClick()
                awaitNoTag(RunTags.test("Suite1", "test1()"))
                renderFrame()
            }
        }
}
