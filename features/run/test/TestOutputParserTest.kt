package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The output of `./kotlin test` (Amper) with a failing and a skipped test, as it was printed.
private val AMPER_OUTPUT =
    """
    ✓ Compilation successful for 'core' [jvm tests]
    Started TmpFailTest
    Started breaks here()
    Failed breaks here()
               => Exception: org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
                    at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:1210)
                    at TmpFailTest.breaks here(TmpFailTest.kt:7)
    Started passes ok()
    Passed passes ok()
    Skipped is skipped()
               => Reason: public final void TmpFailTest.is skipped() is @Disabled
    Completed TmpFailTest

    Failures (1):
      JUnit Jupiter:TmpFailTest:breaks here()
        MethodSource [className = 'TmpFailTest', methodName = 'breaks here', methodParameterTypes = '']
        => org.opentest4j.AssertionFailedError: expected: <1> but was: <2>

    Test run finished after 120 ms
    [         3 tests found           ]
    """.trimIndent()

private fun parse(output: String): TestOutputParser {
    val parser = TestOutputParser()
    output.lines().forEach(parser::accept)
    return parser
}

class TestOutputParserTest {
    @Test
    fun `the tests of an Amper run are grouped by class with their outcome`() {
        val suites = parse(AMPER_OUTPUT).results()
        assertEquals(listOf("TmpFailTest"), suites.map { it.name })
        val tests = suites.single().tests
        assertEquals(listOf("breaks here()", "passes ok()", "is skipped()"), tests.map { it.name })
        assertEquals(listOf(TestStatus.Failed, TestStatus.Passed, TestStatus.Skipped), tests.map { it.status })
    }

    @Test
    fun `a failed test keeps what the runner printed under it, and the summary does not repeat it`() {
        val failed =
            parse(AMPER_OUTPUT)
                .results()
                .single()
                .tests
                .first()
        assertEquals(3, failed.details.size)
        assertTrue(failed.details.first().startsWith("Exception: org.opentest4j.AssertionFailedError"))
        assertTrue(failed.details.last().contains("TmpFailTest.kt:7"))
    }

    @Test
    fun `a skipped test keeps its reason`() {
        val skipped =
            parse(AMPER_OUTPUT)
                .results()
                .single()
                .tests
                .last()
        assertEquals(listOf("Reason: public final void TmpFailTest.is skipped() is @Disabled"), skipped.details)
    }

    @Test
    fun `a test that started and did not end is running`() {
        val parser = parse("Started MyTest\nStarted slow()")
        val test =
            parser
                .results()
                .single()
                .tests
                .single()
        assertEquals(TestStatus.Running, test.status)
        assertEquals(TestStatus.Running, parser.results().single().status)
    }

    @Test
    fun `the suite fails when one of its tests failed`() {
        val suite = parse(AMPER_OUTPUT).results().single()
        assertEquals(TestStatus.Failed, suite.status)
        assertEquals(TestTotals(passed = 1, failed = 1, skipped = 1), totalsOf(listOf(suite)))
    }

    @Test
    fun `gradle results name the class and the outcome on one line`() {
        val output =
            """
            > Task :test

            MyTest > adds() PASSED

            MyTest > divides() FAILED
                java.lang.ArithmeticException at MyTest.kt:12

            Other > skips() SKIPPED
            """.trimIndent()
        val suites = parse(output).results()
        assertEquals(listOf("MyTest", "Other"), suites.map { it.name })
        val divides = suites.first().tests.last()
        assertEquals(TestStatus.Failed, divides.status)
        assertEquals(listOf("java.lang.ArithmeticException at MyTest.kt:12"), divides.details)
        assertEquals(
            TestStatus.Skipped,
            suites
                .last()
                .tests
                .single()
                .status,
        )
    }

    @Test
    fun `an output without tests has no results`() {
        val parser = parse("Compiling...\ne: file:///p/A.kt:1:1 error\nBUILD FAILED")
        assertFalse(parser.hasResults)
        assertTrue(parser.results().isEmpty())
    }

    @Test
    fun `a result without its start still counts`() {
        val tests = parse("Passed alone()").results().single().tests
        assertEquals(listOf("alone()"), tests.map { it.name })
        assertEquals(TestStatus.Passed, tests.single().status)
    }
}
