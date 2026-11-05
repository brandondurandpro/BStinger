package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.writeText
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

// The timeout of the command left hanging: long enough for its helper to start, even on a slow Windows runner.
private const val TIMEOUT_SECONDS = 5L
private const val BEATS_PER_SECOND = 5

// How long the helper must stay silent to count as stopped: many of its beats, even on a slow runner.
private const val QUIET_MILLIS = 2_000L

/** How Git is run: both outputs read, standard input closed, a timeout, and a cancellation that stops it. */
class GitProcessesTest {
    private val repository = TestRepository("git-processes")

    // Outside the repository, so that committing it never takes them.
    private val helperDirectory = Files.createTempDirectory("git-processes-helper")
    private val beatFile = helperDirectory.resolve("helper.beat")

    // A shell script that appends to [beatFile] five times a second, for [HELPER_SECONDS] at most. The tests tell
    // whether it runs by that file growing, never by a process id: under Git for Windows its shell is MSYS2's, whose
    // ids and parents Java does not see reliably, and stopping a process found by a wrong id could stop anything,
    // the CI runner included.
    private val helper: String
        get() {
            val target = beatFile.toString().replace('\\', '/')
            val script = helperDirectory.resolve("helper.sh")
            val beats = HELPER_SECONDS * BEATS_PER_SECOND
            script.writeText(
                "i=0\nwhile [ \$i -lt $beats ]; do echo . >> '$target'; sleep 0.2; i=\$((i + 1)); done\n",
            )
            return "sh '${script.toString().replace('\\', '/')}'"
        }

    @AfterTest
    fun cleanup() {
        // A failing test must not leave its helper behind: only processes this JVM started are stopped.
        ProcessHandle
            .current()
            .descendants()
            .filter(::isLeftover)
            .forEach { it.destroyForcibly() }
        repository.delete()
        helperDirectory.toFile().deleteRecursively()
    }

    private fun isLeftover(process: ProcessHandle): Boolean {
        val name = File(process.info().command().orElse("")).name.removeSuffix(".exe")
        return name == "sleep" || name == "head"
    }

    // How much the helper wrote so far.
    private fun beats(): Long = runCatching { Files.size(beatFile) }.getOrDefault(0L)

    private fun helperStarted(): Boolean = beats() > 0

    // Waits until the helper writes again.
    private fun awaitHelperRunning(what: String) {
        val before = beats()
        awaitCondition(what) { beats() > before }
    }

    // Waits until the helper has written nothing for [QUIET_MILLIS].
    private fun awaitHelperStopped(what: String) {
        var last = beats()
        var changed = System.currentTimeMillis()
        awaitCondition(what) {
            val now = beats()
            if (now != last) {
                last = now
                changed = System.currentTimeMillis()
            }
            System.currentTimeMillis() - changed >= QUIET_MILLIS
        }
    }

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
        var result: Result<GitRun>? = null
        val worker =
            thread(isDaemon = true) {
                val args = listOf("-c", "alias.nap=!$helper", "nap")
                result = runCatching { execGit(repository.path, args, timeoutSeconds = TIMEOUT_SECONDS) }
            }
        awaitCondition("the helper of the alias to start") { helperStarted() }
        worker.join(WAIT_MILLIS)
        val ended = checkNotNull(result) { "The command was still blocked after $WAIT_MILLIS ms" }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val error = ended.exceptionOrNull()
        assertTrue(error is IOException && "timed out" in error.message.orEmpty(), "Ended with $ended")
        assertTrue(elapsedMillis < HELPER_SECONDS * 1000L / 2, "Stopped after $elapsedMillis ms")
        awaitHelperStopped("the helper of the alias to stop")
    }

    @Test
    fun `a command whose helper keeps its output open after it exited is not waited for until its timeout`() {
        repository.commitAll()
        val started = System.nanoTime()
        // The helper, left running in the background, holds the output of Git, which exits at once.
        val alias = "alias.detach=!$helper &"
        val result =
            withinTimeout("The command") {
                runCatching {
                    execGit(repository.path, listOf("-c", alias, "detach"), timeoutSeconds = HELPER_SECONDS * 2L)
                }
            }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < HELPER_SECONDS * 1000L / 2, "Ended after $elapsedMillis ms")
        // Either the reading was cut short, or, on Linux and macOS, it had not begun when Git exited: the JDK then
        // takes what Git left in the pipe and closes it (ProcessPipeInputStream.processExited), and Git printed
        // nothing. Both end long before the timeout.
        val error = result.exceptionOrNull()
        val cutShort = error is IOException && "kept its output open" in error.message.orEmpty()
        val drained = result.getOrNull()?.lines?.isEmpty() == true
        assertTrue(cutShort || drained, "Ended with $result")
    }

    @Test
    fun `cancelling a request stops Git and the helper it runs`() =
        runBlocking {
            // A textconv helper that hangs, as a slow or broken one does: `git diff` runs it. The command is the helper
            // alone, as a real one (`pdftotext - -`) is: on Windows, Git runs a compound command (`sh 'helper'; cat`)
            // through an extra MSYS2 shell whose Windows parent link Java cannot see, so no kill could reach it.
            repository.write(".gitattributes", "*.slow diff=slow\n")
            repository.write("a.slow", "one\n")
            repository.commitAll()
            repository.git("config", "diff.slow.textconv", helper)
            repository.write("a.slow", "two\n")
            val client = CliGitClient()
            // Not a child of runBlocking, which would wait for it without end if the cancellation did not stop it.
            val requests = CoroutineScope(Dispatchers.Default)
            try {
                val request = requests.async { runCatching { client.diff(repository.path, "a.slow") } }
                awaitCondition("the textconv helper to start") {
                    // A diff that ended first never ran the helper: say what it answered.
                    val answer = if (request.isCompleted) runBlocking { request.await() } else null
                    check(answer == null) { "The diff ended without the helper: $answer" }
                    helperStarted()
                }
                awaitHelperRunning("the textconv helper to run")

                withTimeout(WAIT_MILLIS) { request.cancelAndJoin() }
                awaitHelperStopped("the textconv helper to stop")
            } finally {
                requests.cancel()
            }
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
