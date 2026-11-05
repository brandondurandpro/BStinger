package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The helper the tests leave hanging: `sleep` for a number of seconds no other test uses.
private const val HELPER_SECONDS = 41
private const val WAIT_MILLIS = 15_000L
private const val POLL_MILLIS = 20L
private const val FLOOD_BYTES = 200_000

/** How Git is run: both outputs read, standard input closed, a timeout, and a cancellation that stops it. */
class GitProcessesTest {
    private val repository = TestRepository("git-processes")
    private val pidFile = repository.root.resolve("helper.pid")

    // A shell command that writes the process id of its shell to [pidFile], then hangs. Under Git for Windows, whose
    // shell is MSYS2's, the id is the Windows one (`/proc/$$/winpid`): the tests find their helper by that id rather
    // than by the name of a process.
    private val helper: String
        get() {
            val target = pidFile.toString().replace('\\', '/')
            return "(cat /proc/\$\$/winpid 2>/dev/null || echo \$\$) > '$target'; sleep $HELPER_SECONDS"
        }

    @AfterTest
    fun cleanup() {
        // A failing test must not leave its helper behind.
        helperProcess()?.destroyForcibly()
        ProcessHandle
            .current()
            .descendants()
            .filter(::isLeftover)
            .forEach { it.destroyForcibly() }
        repository.delete()
    }

    private fun isLeftover(process: ProcessHandle): Boolean {
        val name = File(process.info().command().orElse("")).name.removeSuffix(".exe")
        return name == "sleep" || name == "head"
    }

    // The id the helper wrote, once it wrote all of it.
    private fun helperPid(): Long? {
        val text = runCatching { Files.readString(pidFile) }.getOrNull()
        return text?.trim()?.toLongOrNull()
    }

    // The shell of the helper, while it runs.
    private fun helperProcess(): ProcessHandle? {
        val pid = helperPid() ?: return null
        return ProcessHandle.of(pid).orElse(null)
    }

    private fun helperAlive(): Boolean = helperProcess()?.isAlive == true

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Still waiting: $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    // Runs [block] on a thread of its own, so that a hang fails the test instead of blocking the suite.
    private fun <T> withinTimeout(what: String, block: () -> T): T {
        var result: Result<T>? = null
        val worker = thread(isDaemon = true) { result = runCatching(block) }
        worker.join(WAIT_MILLIS)
        val ended = result
        checkNotNull(ended) { "$what was still blocked after $WAIT_MILLIS ms" }
        return ended.getOrThrow()
    }

    @Test
    fun `a flood on the standard error does not block a query`() {
        repository.write("a.kt", "one\n")
        repository.commitAll()
        val flood = "alias.flood=!head -c $FLOOD_BYTES /dev/zero >&2"
        val lines = withinTimeout("The query") { gitOutput(repository.path, "-c", flood, "flood") }
        assertEquals(emptyList(), lines)
        val output = withinTimeout("The command") { runGit(repository.path, "-c", flood, "flood") }
        assertEquals("", output)
    }

    @Test
    fun `a command that reads its standard input gets its end at once`() {
        repository.commitAll()
        val lines = withinTimeout("hash-object --stdin") { gitOutput(repository.path, "hash-object", "--stdin") }
        // The object name of an empty blob.
        assertEquals(listOf("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"), lines)
    }

    @Test
    fun `failures keep their meaning, null for a query and Git's message for a command`() {
        repository.write("a.kt", "one\n")
        repository.commitAll()
        assertNull(gitOutput(repository.path, "rev-parse", "--verify", "--quiet", "no-such-branch"))
        val error = assertFailsWith<IOException> { runGit(repository.path, "checkout", "no-such-branch") }
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("git checkout: error:") && "no-such-branch" in message, message)
        assertEquals(listOf("one"), gitOutput(repository.path, "show", "HEAD:a.kt"))
    }

    @Test
    fun `a command running past its timeout is stopped with the processes it started`() {
        repository.commitAll()
        val started = System.nanoTime()
        val error =
            assertFailsWith<IOException> {
                withinTimeout("The command") {
                    execGit(repository.path, listOf("-c", "alias.nap=!$helper", "nap"), timeoutSeconds = 1)
                }
            }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("timed out" in error.message.orEmpty(), error.message)
        assertTrue(elapsedMillis < HELPER_SECONDS * 1000L / 2, "Stopped after $elapsedMillis ms")
        assertTrue(helperPid() != null, "The helper of the alias never started")
        awaitCondition("the helper of the alias to stop") { !helperAlive() }
    }

    @Test
    fun `cancelling a request stops Git and the helper it runs`() =
        runBlocking {
            // A textconv helper that hangs, as a slow or broken one does: `git diff` runs it.
            repository.write(".gitattributes", "*.slow diff=slow\n")
            repository.write("a.slow", "one\n")
            repository.commitAll()
            repository.git("config", "diff.slow.textconv", "$helper; cat")
            repository.write("a.slow", "two\n")
            val client = CliGitClient()
            val request = async(Dispatchers.Default) { runCatching { client.diff(repository.path, "a.slow") } }
            awaitCondition("the textconv helper to start") {
                // A diff that ended first never ran the helper: say what it answered.
                val answer = if (request.isCompleted) runBlocking { request.await() } else null
                check(answer == null) { "The diff ended without the helper: $answer" }
                helperPid() != null
            }
            assertTrue(helperAlive(), "The helper is not running")

            withTimeout(WAIT_MILLIS) { request.cancelAndJoin() }
            awaitCondition("the textconv helper to stop") { !helperAlive() }
        }

    @Test
    fun `concurrent commits of one repository do not collide on the index lock`() =
        runBlocking {
            val count = 8
            repository.write("base.kt", "base\n")
            repository.commitAll()
            repeat(count) { repository.write("file$it.kt", "$it\n") }
            val client = CliGitClient()
            (0 until count)
                .map { async(Dispatchers.IO) { client.commit(repository.path, "commit $it", listOf("file$it.kt")) } }
                .awaitAll()
            assertTrue(client.status(repository.path).isEmpty(), "Left: ${client.status(repository.path)}")
            assertEquals("${count + 1}", repository.git("rev-list", "--count", "HEAD").trim())
        }
}
