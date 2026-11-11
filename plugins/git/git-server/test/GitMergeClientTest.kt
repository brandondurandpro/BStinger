package org.example.kotlineditor.git.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.GitFileStatus
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CRLF = "" + '\r' + '\n'

/** The merge of a conflicted file through Git: its three versions, the history of each side, and its resolution. */
class GitMergeClientTest {
    private val root = Files.createTempDirectory("git-merge")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private fun commitAll() {
        git("init", "-q")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    private fun text(vararg lines: String): ByteArray = lines.joinToString("\n", postfix = "\n").toByteArray()

    private fun latin(vararg lines: String): ByteArray {
        val joined = lines.joinToString(CRLF, postfix = CRLF)
        return joined.toByteArray(Charsets.ISO_8859_1)
    }

    private fun merge() {
        assertFailsWith<IllegalStateException> { git("merge", "--no-edit", "feature") }
    }

    // A repository in the middle of a merge of `feature` that conflicts on [name].
    private fun conflictOn(name: String, base: ByteArray, ours: ByteArray, theirs: ByteArray) {
        root.resolve(name).toFile().writeBytes(base)
        commitAll()
        val current = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        git("checkout", "-q", "-b", "feature")
        root.resolve(name).toFile().writeBytes(theirs)
        git("commit", "-q", "-am", "theirs")
        git("checkout", "-q", current)
        root.resolve(name).toFile().writeBytes(ours)
        git("commit", "-q", "-am", "ours")
        merge()
    }

    private fun conflictOnText(name: String) =
        conflictOn(name, text("1", "2", "3"), text("1", "ours", "3"), text("1", "theirs", "3"))

    @Test
    fun `a merge conflict is listed as conflicted and gives its three versions`() =
        runBlocking {
            conflictOnText("a.txt")
            val client = CliGitClient()

            assertEquals(GitFileStatus.Conflicted, client.status(root.toString())["a.txt"])
            val sides = client.mergeSides(root.toString(), "a.txt")

            assertEquals(listOf("1", "2", "3", ""), sides.base?.lines)
            assertEquals(listOf("1", "ours", "3", ""), sides.ours?.lines)
            assertEquals(listOf("1", "theirs", "3", ""), sides.theirs?.lines)
        }

    @Test
    fun `a file added on both sides has no base`() =
        runBlocking {
            root.resolve("keep.txt").writeText("keep\n")
            commitAll()
            val current = git("rev-parse", "--abbrev-ref", "HEAD").trim()
            git("checkout", "-q", "-b", "feature")
            root.resolve("new.txt").toFile().writeBytes(text("theirs"))
            git("add", "new.txt")
            git("commit", "-q", "-m", "theirs")
            git("checkout", "-q", current)
            root.resolve("new.txt").toFile().writeBytes(text("ours"))
            git("add", "new.txt")
            git("commit", "-q", "-m", "ours")
            merge()

            val sides = CliGitClient().mergeSides(root.toString(), "new.txt")

            assertEquals(null, sides.base)
            assertEquals(listOf("ours", ""), sides.ours?.lines)
            assertEquals(listOf("theirs", ""), sides.theirs?.lines)
        }

    @Test
    fun `a file that is not in conflict has no versions to merge`() {
        root.resolve("a.txt").writeText("1\n")
        commitAll()

        assertFailsWith<IOException> { runBlocking { CliGitClient().mergeSides(root.toString(), "a.txt") } }
    }

    @Test
    fun `a conflict on a binary file cannot be merged line by line`() {
        conflictOn("b.bin", byteArrayOf(0, 1, 2), byteArrayOf(0, 1, 3), byteArrayOf(0, 1, 4))

        assertFailsWith<IOException> { runBlocking { CliGitClient().mergeSides(root.toString(), "b.bin") } }
    }

    @Test
    fun `resolving writes the result, stages the file and ends the conflict`() =
        runBlocking {
            conflictOnText("a.txt")
            val client = CliGitClient()
            val result = FileContent.Text(listOf("1", "ours", "theirs", "3", ""))

            client.resolveConflict(root.toString(), "a.txt", result)

            assertEquals(String(text("1", "ours", "theirs", "3")), root.resolve("a.txt").readText())
            assertEquals("", git("diff", "--name-only", "--diff-filter=U").trim())
            assertTrue(client.status(root.toString())["a.txt"] != GitFileStatus.Conflicted)
            assertFailsWith<IOException> { client.resolveConflict(root.toString(), "a.txt", result) }
            Unit
        }

    @Test
    fun `resolving keeps the encoding and the line endings of the file`() =
        runBlocking {
            conflictOn(
                "l.txt",
                latin("a", "b", "c"),
                latin("a", "b é", "c"),
                latin("a", "b ü", "c"),
            )
            val client = CliGitClient()

            val sides = client.mergeSides(root.toString(), "l.txt")
            assertEquals(CRLF, sides.ours?.lineSeparator)
            assertEquals(listOf("a", "b é", "c", ""), sides.ours?.lines)
            val result = FileContent.Text(listOf("a", "b é", "c", ""), CRLF)
            client.resolveConflict(root.toString(), "l.txt", result)

            val expected = latin("a", "b é", "c")
            assertTrue(expected.contentEquals(root.resolve("l.txt").toFile().readBytes()))
        }

    @Test
    fun `the merge history lists the commits of the file on each side since they diverged`() =
        runBlocking {
            conflictOnText("a.txt")

            val history = CliGitClient().mergeHistory(root.toString(), "a.txt")

            assertEquals(listOf("ours"), history.ours.map { it.subject })
            assertEquals(listOf("theirs"), history.theirs.map { it.subject })
        }

    @Test
    fun `without a merge in progress there is no merge history`() {
        root.resolve("a.txt").writeText("1\n")
        commitAll()

        assertFailsWith<IOException> { runBlocking { CliGitClient().mergeHistory(root.toString(), "a.txt") } }
    }
}
