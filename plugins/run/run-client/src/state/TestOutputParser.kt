package org.example.kotlineditor.state

private const val STARTED = "Started "
private const val PASSED = "Passed "
private const val FAILED = "Failed "
private const val SKIPPED = "Skipped "
private const val COMPLETED = "Completed "

// Gradle: `MyTest > a test() FAILED`, with the failure indented under it.
private val GRADLE_RESULT = Regex("""^(\S.*?) > (.+) (PASSED|FAILED|SKIPPED)$""")

private const val NO_SUITE = "Tests"

/**
 * Reads the console output of a test run into the tree of results, line by line, as the output comes.
 *
 * Two formats are understood: Amper's (`Started MyTest`, `Started it works()`, `Passed it works()`, `Failed ...`
 * followed by the indented failure, `Completed MyTest`) and Gradle's `MyTest > it works() PASSED`. Everything else,
 * the compiler messages and the summaries, is left to the output pane.
 */
class TestOutputParser {
    private class MutableTest(var name: String, var status: TestStatus, val details: MutableList<String>)

    private val suites = LinkedHashMap<String, MutableList<MutableTest>>()
    private var currentSuite: String? = null
    private var detailed: MutableTest? = null

    /** Whether a result was read. */
    val hasResults: Boolean get() = suites.isNotEmpty()

    /** The results read so far; a test still running has the status [TestStatus.Running]. */
    fun results(): List<TestSuite> =
        suites.map { (name, tests) ->
            TestSuite(name, tests.map { TestCase(it.name, it.status, it.details.toList()) })
        }

    /** Reads one more line of the output. */
    fun accept(line: String) {
        val indented = line.startsWith(" ") || line.startsWith("\t")
        if (indented) {
            addDetail(line)
            return
        }
        detailed = null
        when {
            line.startsWith(STARTED) -> started(line.removePrefix(STARTED).trim())
            line.startsWith(PASSED) -> finished(line.removePrefix(PASSED).trim(), TestStatus.Passed)
            line.startsWith(FAILED) -> finished(line.removePrefix(FAILED).trim(), TestStatus.Failed)
            line.startsWith(SKIPPED) -> finished(line.removePrefix(SKIPPED).trim(), TestStatus.Skipped)
            line.startsWith(COMPLETED) -> completed(line.removePrefix(COMPLETED).trim())
            else -> gradle(line)
        }
    }

    private fun started(name: String) {
        if (name.isEmpty()) return
        if (!isTest(name)) {
            currentSuite = name
            suites.getOrPut(name) { ArrayList() }
            return
        }
        val tests = suites.getOrPut(suiteName()) { ArrayList() }
        tests += MutableTest(name, TestStatus.Running, ArrayList())
    }

    private fun finished(name: String, status: TestStatus) {
        if (name.isEmpty() || !isTest(name)) return
        val tests = suites.getOrPut(suiteName()) { ArrayList() }
        val running = tests.lastOrNull { it.name == name && it.status == TestStatus.Running }
        val test = running ?: MutableTest(name, status, ArrayList()).also { tests += it }
        test.status = status
        detailed = test
    }

    private fun completed(name: String) {
        if (name == currentSuite) currentSuite = null
    }

    private fun gradle(line: String) {
        val match = GRADLE_RESULT.matchEntire(line) ?: return
        val suite = match.groupValues[1]
        val name = match.groupValues[2]
        val status = gradleStatus(match.groupValues[3])
        val tests = suites.getOrPut(suite) { ArrayList() }
        val test = MutableTest(name, status, ArrayList())
        tests += test
        detailed = test
    }

    private fun gradleStatus(word: String): TestStatus =
        when (word) {
            "FAILED" -> TestStatus.Failed
            "SKIPPED" -> TestStatus.Skipped
            else -> TestStatus.Passed
        }

    private fun addDetail(line: String) {
        val test = detailed ?: return
        if (test.status == TestStatus.Passed) return
        val text = line.trim().removePrefix("=> ")
        if (text.isNotEmpty()) test.details += text
    }

    // A test is named like a call (`it works()`, `row(1, 2)`); a class is not.
    private fun isTest(name: String): Boolean = name.endsWith(")")

    private fun suiteName(): String = currentSuite ?: NO_SUITE
}
