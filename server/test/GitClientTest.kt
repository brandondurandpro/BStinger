package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.GitFileStatus
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

    // The project tracks a bare remote on its default branch, and a clone that can advance the remote.
    private fun projectWithRemote(): Pair<String, Path> {
        val remote = temporaryDirectory("git-remote")
        runGit(remote, "init", "-q", "--bare")
        root.resolve("a.kt").writeText("one\n")
        commitAll()
        val default = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        git("remote", "add", "origin", remote.toString())
        git("push", "-q", "-u", "origin", "HEAD")
        return default to remote
    }

    private fun cloneOf(remote: Path): Path {
        val clone = temporaryDirectory("git-clone")
        runGit(clone, "clone", "-q", "-c", "core.autocrlf=false", remote.toString(), ".")
        runGit(clone, "config", "user.name", "Test")
        runGit(clone, "config", "user.email", "test@example.com")
        return clone
    }

    @Test
    fun `a branch that is not checked out is pushed, and one without upstream gets one on origin`() =
        runBlocking {
            val (default, remote) = projectWithRemote()
            git("checkout", "-q", "-b", "feature")
            git("push", "-q", "-u", "origin", "feature")
            root.resolve("b.kt").writeText("feature\n")
            git("add", "-A")
            git("commit", "-q", "-m", "feature work")
            git("branch", "fresh")
            git("checkout", "-q", default)
            val client = CliGitClient()

            client.pushBranch(root.toString(), "feature")
            assertEquals(git("rev-parse", "feature").trim(), runGit(remote, "rev-parse", "feature").trim())
            // Pushing again changes nothing and is not an error: a retry after a lost answer is safe.
            client.pushBranch(root.toString(), "feature")

            client.pushBranch(root.toString(), "fresh")
            assertEquals("origin/fresh", git("rev-parse", "--abbrev-ref", "fresh@{upstream}").trim())
            assertEquals(default, client.branch(root.toString()))
        }

    @Test
    fun `a branch that is not checked out is fast-forwarded, and a diverged one is refused`() =
        runBlocking {
            val (default, remote) = projectWithRemote()
            git("branch", "feature")
            git("push", "-q", "-u", "origin", "feature")
            git("branch", "local-only")
            val clone = cloneOf(remote)
            runGit(clone, "checkout", "-q", "feature")
            clone.resolve("remote.kt").writeText("remote\n")
            runGit(clone, "add", "-A")
            runGit(clone, "commit", "-q", "-m", "remote work")
            runGit(clone, "push", "-q")
            val client = CliGitClient()

            client.pullBranch(root.toString(), "feature")
            assertEquals(runGit(remote, "rev-parse", "feature").trim(), git("rev-parse", "feature").trim())
            assertEquals(default, client.branch(root.toString()))
            assertFalse(root.resolve("remote.kt").toFile().exists(), "the working tree must not change")
            client.pullBranch(root.toString(), "feature")

            // A local commit on top of an older remote commit: the branches diverged, nothing is merged.
            runGit(clone, "commit", "-q", "--allow-empty", "-m", "more remote work")
            runGit(clone, "push", "-q")
            git("checkout", "-q", "feature")
            git("commit", "-q", "--allow-empty", "-m", "local work")
            git("checkout", "-q", default)
            val before = git("rev-parse", "feature").trim()
            assertFailsWith<IOException> { client.pullBranch(root.toString(), "feature") }
            assertEquals(before, git("rev-parse", "feature").trim())

            val error = assertFailsWith<IOException> { client.pullBranch(root.toString(), "local-only") }
            assertTrue("no remote" in error.message.orEmpty(), "${error.message}")
        }

    @Test
    fun `pulling the current branch through pullBranch updates the working tree`() =
        runBlocking {
            val (default, remote) = projectWithRemote()
            val clone = cloneOf(remote)
            clone.resolve("remote.kt").writeText("remote\n")
            runGit(clone, "add", "-A")
            runGit(clone, "commit", "-q", "-m", "remote work")
            runGit(clone, "push", "-q")

            CliGitClient().pullBranch(root.toString(), default)
            assertEquals("remote\n", root.resolve("remote.kt").readText())
        }

    @Test
    fun `the log pages through the commits of a branch, newest first, with their refs`() =
        runBlocking {
            val (default, _) = projectWithRemote()
            repeat(5) { git("commit", "-q", "--allow-empty", "-m", "commit $it\twith a tab, a | and été") }
            git("tag", "v1")
            git("branch", "feature")
            val client = CliGitClient()

            val first = client.log(root.toString(), default, skip = 0, limit = 4)
            assertEquals(listOf(4, 3, 2, 1).map { "commit $it\twith a tab, a | and été" }, first.map { it.subject })
            assertTrue(first[0].refs.containsAll(listOf("tag: v1", default, "feature")), "${first[0].refs}")
            assertEquals(listOf(first[1].hash), first[0].parents)
            assertEquals("Test", first[0].author)
            assertTrue(first[0].time > 0)
            val second = client.log(root.toString(), default, skip = 4, limit = 4)
            assertEquals(listOf("commit 0\twith a tab, a | and été", "init"), second.map { it.subject })
            assertTrue(second.last().parents.isEmpty())
            assertTrue("origin/$default" in second.last().refs)
            assertTrue(client.log(root.toString(), default, skip = 6, limit = 4).isEmpty())
            // The remote branch was pushed before the five commits.
            assertEquals(listOf("init"), client.log(root.toString(), "origin/$default", 0, 10).map { it.subject })
        }

    @Test
    fun `commit details list the message and the changed files, renames included`() =
        runBlocking {
            root.resolve("old name.kt").writeText(
                """
                val a = 1
                val b = 2
                val c = 3

                """.trimIndent(),
            )
            root.resolve("gone.kt").writeText("gone\n")
            commitAll()
            val client = CliGitClient()
            val initial = client.log(root.toString(), "HEAD", 0, 1).single()
            val initialFiles = client.commitDetails(root.toString(), initial.hash).files
            assertEquals(setOf("gone.kt", "old name.kt"), initialFiles.map { it.path }.toSet())

            git("mv", "old name.kt", "new name.kt")
            git("rm", "-q", "gone.kt")
            root.resolve("added.kt").writeText("added\n")
            git("add", "-A")
            git("commit", "-q", "-m", "Rework\n\nThe body explains why.")
            val head = client.log(root.toString(), "HEAD", 0, 1).single()
            val details = client.commitDetails(root.toString(), head.hash)
            assertEquals("Rework\n\nThe body explains why.", details.message)
            val byPath = details.files.associateBy { it.path }
            assertEquals(GitFileStatus.Renamed, byPath.getValue("new name.kt").status)
            assertEquals("old name.kt", byPath.getValue("new name.kt").oldPath)
            assertEquals(GitFileStatus.Deleted, byPath.getValue("gone.kt").status)
            assertEquals(GitFileStatus.Added, byPath.getValue("added.kt").status)
            assertFailsWith<IllegalArgumentException> { client.commitDetails(root.toString(), "--help") }
            Unit
        }

    @Test
    fun `remote branches are listed without the remote's HEAD`() =
        runBlocking {
            val (default, _) = projectWithRemote()
            git("push", "-q", "origin", "HEAD:feature")
            git("remote", "set-head", "origin", default)
            git("fetch", "-q")
            val remotes = CliGitClient().remoteBranches(root.toString())
            assertEquals(listOf("origin/$default", "origin/feature").sorted(), remotes)
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
