package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlineditor.lsp.memory.ProcessMemoryReader
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_MS = 60_000L

/** The supervisor against real fake-server processes. */
class LanguageServerSupervisorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root: Path = createTempDirectory("lsp-root")
    private val pids: Path = createTempDirectory("lsp-pids")
    private val reader = ProcessMemoryReader.forCurrentOs()
    private val sampler = ProcessMemorySampler(reader, System::currentTimeMillis, scope, intervalMs = 200)
    private val seen = mutableSetOf<Long>()
    private val timings =
        SupervisorTimings(
            idleStopMs = 500,
            initializeTimeoutMs = 3_000,
            shutdownTimeoutMs = 1_000,
            firstBackoffMs = 100,
            maxCrashes = 3,
            leakCheckMs = 60_000,
        )
    private val supervisor = LanguageServerSupervisor(scope, sampler, OrphanReaper(pids), timings)

    @AfterTest
    fun cleanup() {
        supervisor.close()
        sampler.close()
        reader.close()
        scope.cancel()
        seen.forEach { pid -> ProcessHandle.of(pid).ifPresent(OrphanReaper::killTree) }
    }

    private fun target(vararg options: String, name: String = "fake") =
        ServerTarget(LanguageServerSpec(name, "php") { FakeLanguageServerCommand.command(*options) }, root.toString())

    private fun server(id: String): LanguageServerInfo? = supervisor.servers().firstOrNull { it.id == id }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what: ${supervisor.servers()}" }
            Thread.sleep(20)
        }
    }

    private fun awaitState(id: String, state: LanguageServerState): LanguageServerInfo {
        await("$id $state") { server(id)?.state == state }
        return server(id)!!.also { info -> info.pid?.let { seen += it } }
    }

    private fun gone(pid: Long) = !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `a server starts when a session needs it and stops once nobody does`() {
        val target = target()
        supervisor.use("session-1", listOf(target))
        val running = awaitState(target.id, LanguageServerState.Running)
        val pid = assertNotNull(running.pid)
        assertEquals(1, running.users)
        supervisor.release("session-1")
        await("the server to be forgotten") { server(target.id) == null }
        await("process $pid to exit") { gone(pid) }
    }

    @Test
    fun `two sessions share one process, which outlives the first one`() {
        val target = target()
        supervisor.use("a", listOf(target))
        supervisor.use("b", listOf(target))
        val pid = awaitState(target.id, LanguageServerState.Running).pid!!
        assertEquals(2, server(target.id)!!.users)
        supervisor.release("a")
        Thread.sleep(timings.idleStopMs * 2)
        assertEquals(pid, server(target.id)!!.pid)
        assertEquals(1, server(target.id)!!.users)
    }

    @Test
    fun `a user coming back during the idle delay keeps the process`() {
        val target = target()
        supervisor.use("a", listOf(target))
        val pid = awaitState(target.id, LanguageServerState.Running).pid!!
        supervisor.release("a")
        supervisor.use("a", listOf(target))
        Thread.sleep(timings.idleStopMs * 2)
        assertEquals(pid, server(target.id)!!.pid)
    }

    @Test
    fun `a restart sent twice restarts once`() {
        val target = target()
        supervisor.use("a", listOf(target))
        val first = awaitState(target.id, LanguageServerState.Running)
        supervisor.restart(target.id, first.generation)
        supervisor.restart(target.id, first.generation)
        await("the restart") { server(target.id)!!.generation > first.generation }
        val second = awaitState(target.id, LanguageServerState.Running)
        Thread.sleep(500)
        assertEquals(first.generation + 1, server(target.id)!!.generation)
        assertNotEquals(first.pid, second.pid)
        await("the old process to exit") { gone(first.pid!!) }
    }

    @Test
    fun `a server killed during work is restarted after a short wait`() {
        val target = target()
        supervisor.use("a", listOf(target))
        val first = awaitState(target.id, LanguageServerState.Running)
        // What the OOM killer or a native crash does.
        ProcessHandle.of(first.pid!!).get().destroyForcibly()
        await("the restart") { server(target.id)!!.generation > first.generation }
        val second = awaitState(target.id, LanguageServerState.Running)
        assertNotEquals(first.pid, second.pid)
        assertEquals(1, second.recentCrashes)
    }

    @Test
    fun `a server crashing at start stays down after three crashes, with stderr in the reason`() {
        val target = target("--crash-on-start")
        supervisor.use("a", listOf(target))
        val crashed = awaitState(target.id, LanguageServerState.Crashed)
        assertContains(crashed.message.orEmpty(), "Fatal error: fake crash")
        assertEquals(3, crashed.recentCrashes)
        assertNull(crashed.pid)
        assertContains(supervisor.log(target.id), "Fatal error: fake crash")
    }

    @Test
    fun `restarting a crashed server starts it again`() {
        val target = target("--crash-on-start")
        supervisor.use("a", listOf(target))
        val crashed = awaitState(target.id, LanguageServerState.Crashed)
        supervisor.restart(target.id, crashed.generation)
        await("a new attempt") { server(target.id)!!.generation > crashed.generation }
    }

    @Test
    fun `a server that never answers initialize is killed and reported`() {
        val target = target("--hang-on-initialize")
        supervisor.use("a", listOf(target))
        await("the first failure") { server(target.id)!!.message.orEmpty().contains("No answer to initialize") }
        supervisor.stop(target.id)
        awaitState(target.id, LanguageServerState.Stopped)
        Thread.sleep(200)
        assertTrue(liveFakes().isEmpty(), "left running: ${liveFakes()}")
    }

    @Test
    fun `a server that ignores shutdown is killed with its children`() {
        val target = target("--ignore-shutdown", "--child-mb", "8")
        supervisor.use("a", listOf(target))
        val running = awaitState(target.id, LanguageServerState.Running)
        val pid = running.pid!!
        val process = ProcessHandle.of(pid).get()
        await("its child") { process.children().count() > 0 }
        val child = process.children().use { children -> children.findFirst().get() }.pid()
        seen += child
        supervisor.stop(target.id)
        val stopped = awaitState(target.id, LanguageServerState.Stopped)
        assertEquals("Stopped by the user", stopped.message)
        await("the server and its child to exit") { gone(pid) && gone(child) }
        supervisor.start(target.id)
        awaitState(target.id, LanguageServerState.Running)
    }

    @Test
    fun `memory of the server and its children is sampled`() {
        val target = target("--child-mb", "64")
        supervisor.use("a", listOf(target))
        awaitState(target.id, LanguageServerState.Running)
        val os = System.getProperty("os.name")
        if (os.startsWith("Linux") || os.startsWith("Windows")) {
            await("the child's 64 MB") { server(target.id)!!.memory?.privateBytes.orZero() > 64L * 1024 * 1024 }
            assertTrue(server(target.id)!!.memory!!.processCount >= 2)
        }
    }

    @Test
    fun `indexing progress is shown, then the server is running`() {
        val target = target("--progress", "2")
        supervisor.use("a", listOf(target))
        val indexing = awaitState(target.id, LanguageServerState.Indexing)
        assertEquals("Indexing", indexing.progressTitle)
        await("a percentage above zero") { server(target.id)!!.progressPercent.orZero() > 0 }
        awaitState(target.id, LanguageServerState.Running)
        assertNull(server(target.id)!!.progressPercent)
    }

    @Test
    fun `a server flooding stderr keeps working and its log stays bounded`() {
        val target = target("--stderr-flood")
        supervisor.use("a", listOf(target))
        val running = awaitState(target.id, LanguageServerState.Running)
        await("a full log") { supervisor.log(target.id).size == LogRing.DEFAULT_CAPACITY }
        supervisor.restart(target.id, running.generation)
        await("the restart") { server(target.id)!!.generation > running.generation }
        awaitState(target.id, LanguageServerState.Running)
    }

    @Test
    fun `a server that cannot be provided is unavailable with the reason`() {
        val missing = LanguageServerSpec("missing", "php") { throw IOException("PHP not found") }
        val target = ServerTarget(missing, root.toString())
        supervisor.use("a", listOf(target))
        assertEquals("PHP not found", awaitState(target.id, LanguageServerState.Unavailable).message)
        val program = LanguageServerSpec("bad", "php") { listOf(root.resolve("no-such-program").toString()) }
        val bad = ServerTarget(program, root.toString())
        supervisor.use("a", listOf(target, bad))
        assertContains(awaitState(bad.id, LanguageServerState.Unavailable).message.orEmpty(), "Cannot run")
    }

    @Test
    fun `servers left by a crashed IDE server are reaped at the next start`() {
        val orphan = FakeLanguageServerCommand.start()
        seen += orphan.pid()
        val start = OrphanReaper.startOf(orphan.toHandle())
        // Recorded by an IDE server that is gone: pid 1 never started at that time.
        Files.writeString(pids.resolve("999999999-1-${orphan.pid()}"), start.toString())
        val alive = FakeLanguageServerCommand.start()
        seen += alive.pid()
        val self = ProcessHandle.current()
        val ours = pids.resolve("${self.pid()}-${OrphanReaper.startOf(self)}-${alive.pid()}")
        Files.writeString(ours, OrphanReaper.startOf(alive.toHandle()).toString())
        assertEquals(1, OrphanReaper(pids).reap())
        assertTrue(orphan.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(alive.isAlive, "a server of a live IDE server is not an orphan")
    }

    @Test
    fun `closing the supervisor leaves no process`() {
        val targets = listOf(target(), target("--ignore-shutdown", name = "stubborn"))
        supervisor.use("a", targets)
        targets.forEach { awaitState(it.id, LanguageServerState.Running) }
        supervisor.close()
        await("every process to exit") { liveFakes().isEmpty() }
    }

    private fun liveFakes(): List<Long> {
        val children = ProcessHandle.current().descendants().use { stream -> stream.map { it.pid() }.toList() }
        return seen.filter { !gone(it) } + children
    }
}

private fun Long?.orZero() = this ?: 0L

private fun Int?.orZero() = this ?: 0
