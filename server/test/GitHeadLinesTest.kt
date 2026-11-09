package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHeadLinesTest {
    private val root = Files.createTempDirectory("git-head")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun git(vararg args: String) {
        val identity = listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com")
        val process = ProcessBuilder(identity + args).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    private fun headLines(path: String) = runBlocking { CliGitClient().headLines(root.toString(), path) }

    @Test
    fun `the lines come from HEAD, not from the working tree, without a line after the final newline`() {
        root.resolve("a.txt").writeText(lines("one", "two", ""))
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
        root.resolve("a.txt").writeText(lines("one", "changed", "three", ""))
        assertEquals(listOf("one", "two"), headLines("a.txt"))
    }

    @Test
    fun `a file of a subdirectory is found, a file that is not tracked has none`() {
        root.resolve("src").toFile().mkdirs()
        root.resolve("src/b.txt").writeText("b")
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
        root.resolve("new.txt").writeText("new")
        assertEquals(listOf("b"), headLines("src/b.txt"))
        assertNull(headLines("new.txt"))
    }

    @Test
    fun `a CRLF file comes without its carriage returns`() {
        root.resolve("c.txt").writeText("a\r\nb\r\n")
        git("init", "-q")
        git("config", "core.autocrlf", "false")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
        assertEquals(listOf("a", "b"), headLines("c.txt"))
    }

    @Test
    fun `without a commit the index is used`() {
        root.resolve("d.txt").writeText("staged")
        git("init", "-q")
        git("add", "-A")
        assertEquals(listOf("staged"), headLines("d.txt"))
    }
}
