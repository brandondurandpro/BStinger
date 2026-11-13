package org.example.kotlineditor.server

import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.server.ripgrep.RipgrepQuery
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val RG = Path("/tools/rg")
private val ROOT = Path("/project")

private fun query(
    text: String = "query",
    ignoreCase: Boolean = false,
    wholeWord: Boolean = false,
    regex: Boolean = false,
    filePattern: String? = null,
    scopes: List<String> = emptyList(),
    includeIgnored: Boolean = false,
) = RipgrepQuery(ROOT, text, ignoreCase, wholeWord, regex, filePattern, scopes, includeIgnored)

/** The command line [RipgrepRunner.arguments] builds for every Find in Files option. */
class RipgrepRunnerTest {
    private val runner = RipgrepRunner(RG, 1_000_000)

    @Test
    fun `the plain search is literal, case sensitive, hidden files and the ignored directories out`() {
        val arguments = runner.arguments(query())
        assertFalse("--word-regexp" in arguments)
        assertTrue("--hidden" in arguments)
        assertTrue("--fixed-strings" in arguments)
        for (directory in IGNORED_DIRECTORIES) {
            val glob = "!$directory/"
            assertTrue(indexOfGlob(arguments, glob) >= 0, "expected a glob for $directory in $arguments")
        }
        // The pattern travels through stdin (`--file -`), never on the command line (see RipgrepRunner).
        val stdin = arguments.indexOf("--file")
        assertEquals("-", arguments.getOrNull(stdin + 1))
        assertEquals(ROOT.toString(), arguments.last())
    }

    @Test
    fun `ignoring the case is a flag, its absence another one`() {
        val ignoring = runner.arguments(query(ignoreCase = true))
        assertTrue("--ignore-case" in ignoring && "--case-sensitive" !in ignoring)
        val exact = runner.arguments(query(ignoreCase = false))
        assertTrue("--case-sensitive" in exact && "--ignore-case" !in exact)
    }

    @Test
    fun `whole word draws on ripgrep's flag while the query still goes through stdin`() {
        val arguments = runner.arguments(query(wholeWord = true))
        assertTrue("--word-regexp" in arguments)
        assertTrue("--fixed-strings" in arguments)
    }

    @Test
    fun `a regular expression is not fixed to strings`() {
        val arguments = runner.arguments(query(regex = true))
        assertFalse("--fixed-strings" in arguments)
    }

    @Test
    fun `a file pattern is a glob, after the ignored directories' ones so that it decides`() {
        val arguments = runner.arguments(query(filePattern = "*.kt"))
        val plainGlobAt = indexOfGlob(arguments, "*.kt")
        val ignoredGlobs = IGNORED_DIRECTORIES.map { indexOfGlob(arguments, "!$it/") }
        assertTrue(ignoredGlobs.all { it >= 0 }, "expected every ignored directory to be excluded in $arguments")
        val highestIgnored = ignoredGlobs.maxOrNull() ?: 0
        assertTrue(plainGlobAt > highestIgnored, "expected '*.kt' after them in $arguments")
    }

    @Test
    fun `several patterns and exclusions are one glob each, in the order written`() {
        val arguments = runner.arguments(query(filePattern = "*.kt, *.java, !*.xml"))
        val globs = arguments.windowed(2).filter { it[0] == "--glob" }.map { it[1] }
        assertEquals(listOf("*.kt", "*.java", "!*.xml"), globs.takeLast(3))
    }

    @Test
    fun `a scope searches the directory inside the root, normalized like the root is`() {
        val arguments = runner.arguments(query(scopes = listOf("src/../docs")))
        // What the search takes: the scope resolved under the root, made absolute and normalized (a Windows
        // absolute path carries its drive: the expectation is computed with the same calls).
        val searched = ROOT.resolve("src/../docs").toAbsolutePath().normalize()
        assertEquals(searched.toString(), arguments.last())
    }

    private fun absolute(scope: String): String {
        val resolved = ROOT.resolve(scope)
        return resolved
            .toAbsolutePath()
            .normalize()
            .toString()
    }

    @Test
    fun `several scopes are searched together, a scope inside another or repeated only once`() {
        val arguments = runner.arguments(query(scopes = listOf("src", "docs", "src/main", "docs")))
        val searched = listOf("src", "docs").map { absolute(it) }
        assertEquals(searched, arguments.takeLast(2))
        assertEquals("--", arguments[arguments.size - 3])
    }

    @Test
    fun `including the ignored files lets ripgrep see them, without their globs`() {
        val arguments = runner.arguments(query(includeIgnored = true))
        assertTrue("--no-ignore" in arguments)
        assertFalse("--fixed-strings" !in arguments)
        for (directory in IGNORED_DIRECTORIES) {
            assertFalse(indexOfGlob(arguments, "!$directory/") >= 0, "expected no glob for $directory in $arguments")
        }
    }

    @Test
    fun `a search keeps the hidden files visible whatever the ignored files are`() {
        assertTrue("--hidden" in runner.arguments(query(includeIgnored = true)))
        assertTrue("--hidden" in runner.arguments(query()))
    }

    // Index of the `--glob` that carries [value], or -1 when there is none. A named loop, since the guideline
    // bounds the expression depth.
    private fun indexOfGlob(arguments: List<String>, value: String): Int {
        for (index in arguments.indices) {
            if (arguments[index] == "--glob" && arguments.getOrNull(index + 1) == value) return index
        }
        return -1
    }
}
