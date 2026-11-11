package org.example.kotlineditor.state

/** Outcome of one test. */
enum class TestStatus {
    /** Started, not over. */
    Running,

    /** Passed. */
    Passed,

    /** Failed or crashed. */
    Failed,

    /** Skipped or disabled. */
    Skipped,
}

/** One test of a [TestSuite]. */
data class TestCase(
    /** Name of the test, as the test runner prints it (`it works()`). */
    val name: String,
    /** Outcome so far. */
    val status: TestStatus,
    /** What the runner printed under a failed or skipped test: the message, then the stack. */
    val details: List<String> = emptyList(),
)

/** The tests of one class, in the order they ran. */
data class TestSuite(
    /** Name of the class (or of the file, for a runner that names no class). */
    val name: String,
    /** The tests of the class. */
    val tests: List<TestCase>,
) {
    /** The status of the suite: the worst of its tests. */
    val status: TestStatus
        get() =
            when {
                tests.any { it.status == TestStatus.Failed } -> TestStatus.Failed
                tests.any { it.status == TestStatus.Running } -> TestStatus.Running
                tests.isNotEmpty() && tests.all { it.status == TestStatus.Skipped } -> TestStatus.Skipped
                else -> TestStatus.Passed
            }
}

/** The count of tests of each outcome. */
data class TestTotals(val passed: Int = 0, val failed: Int = 0, val skipped: Int = 0, val running: Int = 0) {
    /** Every test counted. */
    val all: Int get() = passed + failed + skipped + running
}

/** The totals of [suites]. */
fun totalsOf(suites: List<TestSuite>): TestTotals {
    var passed = 0
    var failed = 0
    var skipped = 0
    var running = 0
    for (suite in suites) {
        for (test in suite.tests) {
            when (test.status) {
                TestStatus.Passed -> passed++
                TestStatus.Failed -> failed++
                TestStatus.Skipped -> skipped++
                TestStatus.Running -> running++
            }
        }
    }
    return TestTotals(passed, failed, skipped, running)
}
