package org.example.kotlineditor.git.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Branch names checked before they reach Git, and HEAD read from the files of the repository. */
class GitRefsTest {
    private val repository = TestRepository("git-refs")
    private val extra = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        repository.delete()
        extra.forEach { it.toFile().deleteRecursively() }
    }

    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also { extra.add(it) }

    // Whether Git itself accepts [name] as a branch name.
    private fun gitAccepts(name: String): Boolean = repository.gitSucceeds("check-ref-format", "--branch", name)

    // Everything Git can see of the repository: its refs, HEAD, the index and the working tree.
    private fun repositoryState(): String =
        listOf(
            repository.git("for-each-ref"),
            repository.git("symbolic-ref", "-q", "HEAD"),
            repository.git("status", "--porcelain", "-uall"),
            repository.git("diff"),
        ).joinToString("\n")

    @Test
    fun `branch names follow the rules of git check-ref-format`() {
        repository.commitAll()
        // Separated by '|', which none of them holds.
        val valid = "main|feature/login|été-1|fix/a.b|v1.0|a-b_c|x@y|UPPER|a+b|a!b|a{b}|a'b|a\"b|a,b"
        val invalid =
            "-f|--all|-||a..b|a b|a/|/a|a//b|a.lock|a.lock/b|.a|a/.b|a.|a@{b|a\\b|a~1|a^|a:b|a?|a*|a[b|a\tb|" +
                "a\u007fb|a/b.lock/c"
        val names = valid.split('|') + invalid.split('|')
        val differing = names.filter { isBranchName(it) != gitAccepts(it) }
        assertEquals(emptyList(), differing, "Judged unlike Git")
        // Names no branch can have, though `--branch` reads `@` and `@{-1}` as the current and previous branches (and
        // an older Git accepts `HEAD`).
        listOf("HEAD", "@", "@{-1}").forEach { assertFalse(isBranchName(it), it) }
        assertFailsWith<IllegalArgumentException> { requireBranchName("-f") }
    }

    @Test
    fun `a checkout of a name starting with a dash is refused and keeps the changes`() =
        runBlocking {
            repository.write("a.kt", "one\n")
            repository.commitAll()
            val edited = repository.write("a.kt", "uncommitted\n")
            assertFailsWith<IllegalArgumentException> { CliGitClient().checkout(repository.path, "-f") }
            assertEquals("uncommitted\n", edited.readText())
        }

    @Test
    fun `every command taking a branch name refuses an option and changes nothing`() =
        runBlocking {
            repository.write("a.kt", "one\n")
            repository.commitAll()
            repository.git("branch", "feature")
            repository.write("a.kt", "uncommitted\n")
            val before = repositoryState()
            val client = CliGitClient()
            val root = repository.path
            val refusals =
                listOf<suspend () -> Unit>(
                    { client.checkout(root, "--orphan=x") },
                    { client.deleteBranches(root, listOf("feature", "--merged")) },
                    { client.pushBranch(root, "--all") },
                    { client.pullBranch(root, "--all") },
                    { client.worktree(root, "-f") },
                )
            for (refusal in refusals) assertFailsWith<IllegalArgumentException> { refusal() }
            assertEquals(before, repositoryState())
            assertTrue("feature" in client.branches(root))
        }

    @Test
    fun `a branch with an unusual but valid name is still checked out, and its worktree made and removed`() =
        runBlocking {
            repository.write("a.kt", "one\n")
            repository.commitAll()
            repository.git("branch", "feature/été-1")
            val client = CliGitClient()
            client.checkout(repository.path, "feature/été-1")
            assertEquals("feature/été-1", client.branch(repository.path))
            val worktree = client.worktree(repository.path, "agent/task-1")
            assertEquals(worktree, client.worktree(repository.path, "agent/task-1"))
            assertEquals("agent/task-1", client.branch(worktree))
            client.removeWorktree(repository.path, worktree)
            assertFalse(Path.of(worktree).toFile().exists())
        }

    @Test
    fun `HEAD read from the files agrees with Git, loose, packed, detached and in a worktree`() {
        repository.write("a.kt", "one\n")
        repository.commitAll()
        val root = repository.path
        assertEquals(repository.head(), headFromFiles(root))

        repository.git("commit", "-q", "--allow-empty", "-m", "second")
        repository.git("pack-refs", "--all")
        assertEquals(repository.head(), headFromFiles(root), "packed")

        repository.git("checkout", "-q", "--detach")
        assertEquals(repository.head(), headFromFiles(root), "detached")
        repository.git("checkout", "-q", "-")

        repository.git("branch", "other")
        val worktree = temporaryDirectory("git-refs-worktree").resolve("other")
        repository.git("worktree", "add", "-q", worktree.toString(), "other")
        repository.git("commit", "-q", "--allow-empty", "-m", "in the worktree", directory = worktree)
        assertEquals(repository.head(worktree), headFromFiles(worktree.toString()), "worktree")
        assertEquals(repository.head(), headFromFiles(root))
        repository.git("worktree", "remove", "--force", worktree.toString())
    }

    @Test
    fun `HEAD that the files cannot tell is left to Git`() {
        repository.git("init", "-q")
        // A branch without commits.
        assertNull(headFromFiles(repository.path))
        // Outside a repository.
        assertNull(headFromFiles(temporaryDirectory("not-a-repository").toString()))
        // The reftable format (Git 2.45 and later) keeps its refs in binary tables.
        val reftable = temporaryDirectory("git-reftable")
        val created = runCatching { repository.git("init", "-q", "--ref-format=reftable", directory = reftable) }
        if (created.isSuccess) {
            repository.git("commit", "-q", "--allow-empty", "-m", "init", directory = reftable)
            val read = headFromFiles(reftable.toString())
            assertTrue(read == null || read == repository.head(reftable), "reftable: $read")
        }
    }
}
