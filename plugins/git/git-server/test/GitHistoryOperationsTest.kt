package org.example.kotlineditor.git.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.GitResetMode
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHistoryOperationsTest {
    private val root = Files.createTempDirectory("git-history-ops")
    private val client = CliGitClient()
    private val path = root.toString()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private fun init() {
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        commitFile("a.txt", "one\n", "first")
    }

    private fun commitFile(name: String, text: String, message: String): String {
        root.resolve(name).writeText(text)
        git("add", "-A")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    private fun branches() = git("branch", "--format=%(refname:short)").lines()

    @Test
    fun `createBranch makes a branch at a commit, with or without switching, and refuses an existing name`() =
        runBlocking<Unit> {
            init()
            val first = git("rev-parse", "HEAD")
            commitFile("a.txt", "two\n", "second")
            client.createBranch(path, "old", first, switchTo = false)
            assertEquals(first, git("rev-parse", "old"))
            assertEquals("main", git("rev-parse", "--abbrev-ref", "HEAD"))
            client.createBranch(path, "feature", "main", switchTo = true)
            assertEquals("feature", git("rev-parse", "--abbrev-ref", "HEAD"))
            assertFailsWith<IOException> { client.createBranch(path, "old", "main", switchTo = false) }
            assertFailsWith<IllegalArgumentException> { client.createBranch(path, "-f", "main", switchTo = false) }
            assertFailsWith<IllegalArgumentException> { client.createBranch(path, "x", "--force", switchTo = false) }
        }

    @Test
    fun `renameBranch renames, and refuses a missing branch or a taken name`() =
        runBlocking<Unit> {
            init()
            git("branch", "feature")
            git("branch", "taken")
            client.renameBranch(path, "feature", "renamed")
            assertTrue("renamed" in branches())
            assertFalse("feature" in branches())
            assertFailsWith<IOException> { client.renameBranch(path, "renamed", "taken") }
            assertFailsWith<IOException> { client.renameBranch(path, "ghost", "other") }
            assertTrue("renamed" in branches() && "taken" in branches())
        }

    @Test
    fun `checkoutRemote creates a tracking branch, or switches to the existing one`() =
        runBlocking<Unit> {
            init()
            val remote = Files.createTempDirectory("git-history-remote")
            try {
                ProcessBuilder("git", "init", "-q", "--bare").directory(remote.toFile()).start().waitFor()
                git("remote", "add", "origin", remote.toString())
                git("branch", "feature")
                git("push", "-q", "origin", "main", "feature")
                git("branch", "-D", "feature")
                git("fetch", "-q")
                assertEquals("feature", client.checkoutRemote(path, "origin/feature"))
                assertEquals("feature", git("rev-parse", "--abbrev-ref", "HEAD"))
                assertEquals("origin/feature", git("rev-parse", "--abbrev-ref", "feature@{upstream}"))
                git("checkout", "-q", "main")
                assertEquals("feature", client.checkoutRemote(path, "origin/feature"))
                assertFailsWith<IOException> { client.checkoutRemote(path, "origin/ghost") }
            } finally {
                remote.toFile().deleteRecursively()
            }
        }

    @Test
    fun `checkoutCommit detaches HEAD and createTag tags a commit once`() =
        runBlocking<Unit> {
            init()
            val first = git("rev-parse", "HEAD")
            commitFile("a.txt", "two\n", "second")
            client.createTag(path, "v1", first)
            assertEquals(first, git("rev-parse", "v1^{commit}"))
            assertFailsWith<IOException> { client.createTag(path, "v1", first) }
            client.checkoutCommit(path, first)
            assertEquals("HEAD", git("rev-parse", "--abbrev-ref", "HEAD"))
            assertEquals("one\n", root.resolve("a.txt").readText())
            assertFailsWith<IllegalArgumentException> { client.checkoutCommit(path, "--force") }
        }

    @Test
    fun `merge and rebase integrate a branch, and stop on conflicts without failing`() =
        runBlocking<Unit> {
            init()
            git("checkout", "-q", "-b", "side")
            commitFile("b.txt", "side\n", "side work")
            git("checkout", "-q", "main")
            commitFile("a.txt", "main change\n", "main work")
            client.merge(path, "side")
            assertEquals("side\n", root.resolve("b.txt").readText())

            git("checkout", "-q", "-b", "conflict", "HEAD~2")
            commitFile("a.txt", "conflicting\n", "conflicting work")
            val message = client.merge(path, "main")
            assertTrue("conflicts" in message, message)
            assertTrue(git("diff", "--name-only", "--diff-filter=U").contains("a.txt"))
            git("merge", "--abort")

            val rebased = client.rebase(path, "main")
            assertTrue("conflicts" in rebased, rebased)
            git("rebase", "--abort")
            assertEquals("conflict", git("rev-parse", "--abbrev-ref", "HEAD"))
        }

    @Test
    fun `cherryPick and revertCommit apply and undo a commit, and refuse a merge commit`() =
        runBlocking<Unit> {
            init()
            git("checkout", "-q", "-b", "side")
            val picked = commitFile("b.txt", "side\n", "side work")
            git("checkout", "-q", "main")
            client.cherryPick(path, picked)
            assertEquals("side\n", root.resolve("b.txt").readText())
            client.revertCommit(path, git("rev-parse", "HEAD"))
            assertFalse(root.resolve("b.txt").toFile().exists())

            git("checkout", "-q", "side")
            git("merge", "--no-ff", "-q", "-m", "merge", "main")
            val merge = git("rev-parse", "HEAD")
            assertFailsWith<IOException> { client.cherryPick(path, merge) }
            assertFailsWith<IOException> { client.revertCommit(path, merge) }
        }

    @Test
    fun `reset moves the branch and keeps or discards the changes by mode`() =
        runBlocking<Unit> {
            init()
            val first = git("rev-parse", "HEAD")
            commitFile("a.txt", "two\n", "second")

            client.reset(path, first, GitResetMode.Soft)
            assertEquals(first, git("rev-parse", "HEAD"))
            assertEquals("M  a.txt", git("status", "--porcelain"))

            client.reset(path, first, GitResetMode.Mixed)
            assertEquals("M a.txt", git("status", "--porcelain"))
            assertEquals("two\n", root.resolve("a.txt").readText())

            client.reset(path, first, GitResetMode.Hard)
            assertEquals("", git("status", "--porcelain"))
            assertEquals("one\n", root.resolve("a.txt").readText())
            assertFailsWith<IllegalArgumentException> { client.reset(path, "--hard", GitResetMode.Hard) }
        }
}
