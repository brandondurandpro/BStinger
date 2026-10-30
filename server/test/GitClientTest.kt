package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitClientTest {
    private val root = Files.createTempDirectory("git-client")
    private val extra = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
        extra.forEach { it.toFile().deleteRecursively() }
    }

    private fun runGit(directory: Path, vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private fun git(vararg args: String) = runGit(root, *args)

    private fun commitAll() {
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extra.add(it) }

    @Test
    fun `commit stages every change and records the message`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            root.resolve("a.kt").writeText("two\n")
            root.resolve("b.kt").writeText("new\n")
            CliGitClient().commit(root.toString(), "Second commit")
            assertTrue(CliGitClient().status(root.toString()).isEmpty())
            assertEquals("Second commit", runGit(root, "log", "-1", "--format=%s").trim())
            assertEquals("two\n", root.resolve("a.kt").readText())
        }

    @Test
    fun `branches lists local branches and checkout switches between them`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            git("branch", "feature")
            val client = CliGitClient()
            val default = git("rev-parse", "--abbrev-ref", "HEAD").trim()
            assertEquals(setOf("feature", default), client.branches(root.toString()).toSet())
            client.checkout(root.toString(), "feature")
            assertEquals("feature", client.branch(root.toString()))
            client.checkout(root.toString(), default)
            assertEquals(default, client.branch(root.toString()))
        }

    @Test
    fun `blame reports the author and the date of every line`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\ntwo\n")
            commitAll()
            val lines = CliGitClient().blame(root.toString(), "a.kt")
            assertEquals(listOf(0, 1), lines.map { it.line })
            assertTrue(lines.all { it.author == "Test" }, "Authors: ${lines.map { it.author }}")
            assertTrue(lines.all { it.date.matches(Regex("""\d{4}-\d\d-\d\d""")) }, "Dates: ${lines.map { it.date }}")
            assertTrue(lines.all { it.commit.length == 8 }, "Commits: ${lines.map { it.commit }}")
        }

    @Test
    fun `an untracked file has no blame`() =
        runBlocking {
            root.resolve("tracked.kt").writeText("x\n")
            commitAll()
            root.resolve("new.kt").writeText("x\n")
            assertTrue(CliGitClient().blame(root.toString(), "new.kt").isEmpty())
        }

    @Test
    fun `push publishes the commits and pull brings the remote ones`() =
        runBlocking {
            val remote = temporaryDirectory("git-remote")
            runGit(remote, "init", "-q", "--bare")
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            git("remote", "add", "origin", remote.toString())
            git("push", "-q", "-u", "origin", "HEAD")
            root.resolve("a.kt").writeText("two\n")
            git("add", "-A")
            git("commit", "-q", "-m", "two")
            CliGitClient().push(root.toString())
            val clone = temporaryDirectory("git-clone")
            runGit(clone, "clone", "-q", remote.toString(), ".")
            assertEquals("two\n", clone.resolve("a.kt").readText())
            // The clone commits and pushes; the project then pulls that commit.
            clone.resolve("a.kt").writeText("three\n")
            runGit(clone, "add", "-A")
            runGit(clone, "commit", "-q", "-m", "three")
            runGit(clone, "push", "-q")
            CliGitClient().pull(root.toString())
            assertEquals("three\n", root.resolve("a.kt").readText())
            assertFalse(CliGitClient().status(root.toString()).containsKey("a.kt"))
        }
}
