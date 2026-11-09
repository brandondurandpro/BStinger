package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.RunTab
import org.example.kotlineditor.state.TestStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.RunTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// What `./kotlin test` printed for a failing, a skipped and two passing tests.
private val AMPER_RESULTS =
    textOf(
        "Started MathTest",
        "Started adds()",
        "Passed adds()",
        "Started divides()",
        "Failed divides()",
        "           => Exception: java.lang.ArithmeticException: / by zero",
        "                at demo.Math.divide(Math.kt:3)",
        "                at demo.AppTest.greetingIsLoud(AppTest.kt:3)",
        "Skipped later()",
        "           => Reason: not yet",
        "Completed MathTest",
        "Started StringsTest",
        "Started shouts()",
        "Passed shouts()",
        "Completed StringsTest",
        "",
        "Test run finished after 12 ms",
    )

/** The tree of test results of the Run tool window. */
@OptIn(ExperimentalTestApi::class)
class RunTestsE2eTest {
    private val project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a test run shows its results as a tree, the failed suite open`() =
        runComposeUiTest {
            project.file("out.txt", AMPER_RESULTS)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("All tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.SUMMARY).assertTextEquals("2 passed, 1 failed, 1 skipped")
            assertEquals(RunTab.Tests, app.project.run.tab)
            // The suite that has a failure is open, the one that passed is closed.
            awaitTag(RunTags.suite("MathTest")).assertIsDisplayed()
            awaitTag(RunTags.test("MathTest", "divides()")).assertIsDisplayed()
            awaitTag(RunTags.test("MathTest", "adds()")).assertIsDisplayed()
            awaitTag(RunTags.suite("StringsTest")).assertIsDisplayed()
            awaitNoTag(RunTags.test("StringsTest", "shouts()"))
            val statuses = app.project.run.session?.results?.flatMap { it.tests }?.map { it.status }
            assertEquals(listOf(TestStatus.Passed, TestStatus.Failed, TestStatus.Skipped, TestStatus.Passed), statuses)
        }

    @Test
    fun `a suite opens and closes when clicked`() =
        runComposeUiTest {
            project.file("out.txt", AMPER_RESULTS)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("All tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.suite("StringsTest")).performClick()
            awaitTag(RunTags.test("StringsTest", "shouts()")).assertIsDisplayed()
            onNodeWithTag(RunTags.suite("MathTest")).performClick()
            awaitNoTag(RunTags.test("MathTest", "adds()"))
            onNodeWithTag(RunTags.suite("MathTest")).performClick()
            awaitTag(RunTags.test("MathTest", "adds()"))
        }

    @Test
    fun `a failed test shows what the runner printed, with its stack frames as links`() =
        runComposeUiTest {
            project.file("out.txt", AMPER_RESULTS)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("All tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.test("MathTest", "divides()")).performClick()
            awaitTag(RunTags.DETAILS)
            awaitTag(RunTags.detailLine(0)).assertTextEquals("Exception: java.lang.ArithmeticException: / by zero")
            // The frame of a file of the project opens it at the line.
            awaitTag("${RunTags.detailLine(2)}:link:0").performClick()
            awaitTag(EditorTags.tab("src/test/kotlin/AppTest.kt"))
        }

    @Test
    fun `failed only hides the tests that did not fail`() =
        runComposeUiTest {
            project.file("out.txt", AMPER_RESULTS)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("All tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.ONLY_FAILED).performClick()
            awaitNoTag(RunTags.test("MathTest", "adds()"))
            awaitNoTag(RunTags.suite("StringsTest"))
            awaitTag(RunTags.test("MathTest", "divides()")).assertIsDisplayed()
            onNodeWithTag(RunTags.ONLY_FAILED).performClick()
            awaitTag(RunTags.test("MathTest", "adds()"))
        }

    @Test
    fun `the raw output stays one tab away`() =
        runComposeUiTest {
            project.file("out.txt", AMPER_RESULTS)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("All tests", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.TAB_OUTPUT).performClick()
            awaitTag(RunTags.line(0)).assertTextEquals("Started MathTest")
            onNodeWithTag(RunTags.TAB_TESTS).performClick()
            awaitTag(RunTags.SUMMARY)
        }

    @Test
    fun `a test still running is counted as running`() =
        runComposeUiTest {
            project.file("out.txt", textOf("Started SlowTest", "Started waits()"))
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Slow", emit("out.txt", mode = "hang"), RunKind.Tests)
            pressRun()
            awaitCondition { app.project.run.session?.results?.isNotEmpty() == true }
            awaitTag(RunTags.SUMMARY).assertTextEquals("0 passed, 0 failed, 0 skipped, 1 running")
            onNodeWithTag(RunTags.STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
        }

    @Test
    fun `an output without test results says so`() =
        runComposeUiTest {
            project.file("out.txt", "Compiling...\ne: Nowhere.kt:1:1 broken\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Broken build", emit("out.txt", exitCode = 1), RunKind.Tests)
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.NO_TESTS).assertIsDisplayed()
        }

    @Test
    fun `a program configuration has no tests tab`() =
        runComposeUiTest {
            project.file("out.txt", "Started MathTest\nStarted adds()\nPassed adds()\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Program", emit("out.txt"))
            pressRun()
            awaitRunStatus(app, RunStatus.Succeeded)
            awaitTag(RunTags.OUTPUT)
            awaitNoTag(RunTags.TAB_TESTS)
            assertEquals(emptyList(), app.project.run.session?.results)
        }
}
