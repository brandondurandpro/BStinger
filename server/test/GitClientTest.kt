package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        // The app commits and pulls without the test's -c options: a runner has no identity, and Windows converts EOLs.
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
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
    fun `branch details report the current branch and its distance from the remote`() =
        runBlocking {
            val remote = temporaryDirectory("git-remote")
            runGit(remote, "init", "-q", "--bare")
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            val default = git("rev-parse", "--abbrev-ref", "HEAD").trim()
            git("remote", "add", "origin", remote.toString())
            git("push", "-q", "-u", "origin", "HEAD")
            git("branch", "feature")
            // A clone advances the remote; a fetch makes the project see that it is one commit behind.
            val clone = temporaryDirectory("git-clone")
            runGit(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
            runGit(clone, "config", "user.name", "Test")
            runGit(clone, "config", "user.email", "test@example.com")
            clone.resolve("remote.kt").writeText("remote\n")
            runGit(clone, "add", "-A")
            runGit(clone, "commit", "-q", "-m", "remote")
            runGit(clone, "push", "-q")
            git("fetch", "-q")

            val client = CliGitClient()
            val behind = client.branchDetails(root.toString())
            val main = behind.single { it.name == default }
            assertTrue(main.current, "The checked-out branch is not current: $behind")
            assertEquals("origin/$default", main.upstream)
            assertEquals(0, main.ahead)
            assertEquals(1, main.behind)
            assertFalse(main.upstreamGone)
            val feature = behind.single { it.name == "feature" }
            assertFalse(feature.current)
            assertEquals(null, feature.upstream)
            assertEquals(0, feature.behind)

            // A local commit adds one commit to push on top of the one to pull.
            root.resolve("a.kt").writeText("two\n")
            git("add", "-A")
            git("commit", "-q", "-m", "local")
            val ahead = client.branchDetails(root.toString()).single { it.name == default }
            assertEquals(1, ahead.ahead)
            assertEquals(1, ahead.behind)
            assertEquals(setOf(default, "feature"), client.branches(root.toString()).toSet())
        }

    @Test
    fun `deleting branches removes unmerged ones and refuses the current branch`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            val default = git("rev-parse", "--abbrev-ref", "HEAD").trim()
            git("checkout", "-q", "-b", "unmerged")
            root.resolve("a.kt").writeText("two\n")
            git("commit", "-q", "-am", "only on unmerged")
            git("checkout", "-q", default)
            git("branch", "merged")
            val client = CliGitClient()

            client.deleteBranches(root.toString(), listOf("unmerged", "merged"))
            assertEquals(listOf(default), client.branches(root.toString()))

            // A retry after a lost answer finds the branches already gone: that is not an error.
            client.deleteBranches(root.toString(), listOf("unmerged", "merged"))
            assertFailsWith<IllegalArgumentException> { client.deleteBranches(root.toString(), listOf(default)) }
            assertEquals(listOf(default), client.branches(root.toString()))
        }

    @Test
    fun `a branch checked out in another worktree is kept and the others are deleted`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            git("branch", "busy")
            git("branch", "free")
            val worktree = temporaryDirectory("git-worktree")
            worktree.toFile().delete()
            git("worktree", "add", "-q", worktree.toString(), "busy")
            val client = CliGitClient()

            val error = assertFailsWith<IOException> { client.deleteBranches(root.toString(), listOf("busy", "free")) }
            assertTrue("busy" in error.message.orEmpty(), "The error does not name the kept branch: ${error.message}")
            val left = client.branches(root.toString())
            git("worktree", "remove", "--force", worktree.toString())
            assertTrue("busy" in left && "free" !in left, "Branches left: $left")
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
    fun `blame follows the edits of the file and the new commits`() =
        runBlocking {
            root.resolve("a.kt").writeText("one\n")
            commitAll()
            val git = CliGitClient()
            val first = git.blame(root.toString(), "a.kt").single().commit
            assertEquals(first, git.blame(root.toString(), "a.kt").single().commit)

            root.resolve("a.kt").writeText("one\ntwo\n")
            val edited = git.blame(root.toString(), "a.kt")
            assertEquals(listOf(first, "00000000"), edited.map { it.commit })

            git("commit", "-q", "-am", "two")
            val committed = git.blame(root.toString(), "a.kt")
            assertEquals(first, committed[0].commit)
            assertTrue(committed[1].commit != "00000000" && committed[1].commit != first, "Commits: $committed")
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
            runGit(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
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
