package org.example.kotlineditor.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectRunner
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.model.RunStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEPT_LINES = 50_000

// A runner whose commands the test drives: each start opens a channel the test sends chunks to.
private class FakeRunner(private val available: Boolean = true) : ProjectRunner {
    class Started(
        val directory: String,
        val command: List<String>,
        val stream: RunStream,
        val chunks: Channel<RunChunk>,
    )

    val started = ArrayList<Started>()
    val stopped = ArrayList<RunStream>()

    override fun start(directory: String, command: List<String>): RunStream? {
        if (!available) return null
        val chunks = Channel<RunChunk>(Channel.UNLIMITED)
        val stream = RunStream(started.size.toLong() + 1, chunks.receiveAsFlow())
        started += Started(directory, command, stream, chunks)
        return stream
    }

    override fun stop(stream: RunStream) {
        stopped += stream
    }
}

class RunStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val runner = FakeRunner()
    private val opened = ArrayList<Triple<FileEntry, Int, Int>>()

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private fun state(
        root: String = "/work/p",
        preferences: Map<String, String> = emptyMap(),
        projectRunner: ProjectRunner = runner,
        found: List<RunConfiguration> = emptyList(),
        located: FileEntry? = null,
    ) = RunState(
        root,
        projectRunner,
        scope,
        preferences,
        discover = { found },
        locate = { located },
        openAt = { entry, line, column -> opened += Triple(entry, line, column) },
    )

    private fun RunState.add(command: String, kind: RunKind = RunKind.Run, directory: String = ""): RunConfiguration {
        val configuration =
            RunConfiguration(
                "id-${configurations.size}",
                "c${configurations.size}",
                kind,
                command,
                directory,
            )
        save(configuration)
        return configuration
    }

    private fun finish(exitCode: Int? = 0, error: String? = null, seq: Long = 1) =
        RunChunk(seq, finished = true, exitCode = exitCode, error = error)

    @Test
    fun `an exit code of zero succeeds and another one fails`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        runner.started[0].chunks.trySend(finish(0))
        assertEquals(RunStatus.Succeeded, run.session?.status)
        run.runSelected()
        runner.started[1].chunks.trySend(finish(2))
        assertEquals(RunStatus.Failed, run.session?.status)
        assertEquals(2, run.session?.exitCode)
    }

    @Test
    fun `a command that could not start says why`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        runner.started[0].chunks.trySend(finish(exitCode = null, error = "no such program"))
        assertEquals(RunStatus.NotStarted, run.session?.status)
        assertEquals("no such program", run.session?.error)
    }

    @Test
    fun `stopping asks the runner, and the run counts as stopped whatever its exit code`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        run.stop()
        assertEquals(listOf(runner.started[0].stream), runner.stopped)
        runner.started[0].chunks.trySend(finish(143))
        assertEquals(RunStatus.Stopped, run.session?.status)
    }

    @Test
    fun `stopping when nothing runs asks nothing`() {
        val run = state()
        run.add("prog")
        run.stop()
        run.runSelected()
        runner.started[0].chunks.trySend(finish(0))
        run.stop()
        assertTrue(runner.stopped.isEmpty())
    }

    @Test
    fun `a stream that ends without its last chunk is a lost run`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        runner.started[0].chunks.close()
        assertEquals(RunStatus.Lost, run.session?.status)
    }

    @Test
    fun `running a configuration again restarts it, and the old run's late output is not mixed in`() {
        val run = state()
        val configuration = run.add("prog")
        run.run(configuration)
        val first = run.session
        run.run(configuration)
        assertEquals(listOf(runner.started[0].stream), runner.stopped)
        runner.started[0].chunks.trySend(RunChunk(1, listOf("late line from the first")))
        runner.started[1].chunks.trySend(RunChunk(1, listOf("line of the second")))
        assertEquals(listOf("line of the second"), run.session?.lines?.toList())
        assertEquals(listOf("late line from the first"), first?.lines?.toList())
        assertEquals(1, run.sessions.size)
    }

    @Test
    fun `several commands run at the same time, each with its own output`() {
        val run = state()
        val first = run.add("first")
        val second = run.add("second")
        run.run(first)
        run.run(second)
        assertTrue(runner.stopped.isEmpty())
        assertEquals(2, run.sessions.size)
        assertTrue(run.isRunning(first.id))
        assertTrue(run.isRunning(second.id))
        runner.started[0].chunks.trySend(RunChunk(1, listOf("from first")))
        runner.started[1].chunks.trySend(RunChunk(1, listOf("from second")))
        assertEquals(listOf("from second"), run.session?.lines?.toList())
        run.show(run.sessions[0])
        assertEquals(listOf("from first"), run.session?.lines?.toList())
        // The tab shown is the selected command: Start and Stop then act on it.
        assertEquals(first.id, run.selectedId)
    }

    @Test
    fun `stop ends the run shown, stopAll all of them, and closing a run removes it`() {
        val run = state()
        val first = run.add("first")
        val second = run.add("second")
        run.run(first)
        run.run(second)
        run.stop()
        assertEquals(listOf(runner.started[1].stream), runner.stopped)
        run.show(run.sessions[0])
        run.stopAll()
        assertEquals(2, runner.stopped.size)
        val shown = assertNotNull(run.session)
        run.close(shown)
        assertEquals(1, run.sessions.size)
        assertEquals(run.sessions[0], run.session)
    }

    @Test
    fun `lines are kept without colors, and the oldest are dropped past the limit`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        val chunks = runner.started[0].chunks
        chunks.trySend(RunChunk(1, listOf("\u001B[31mred\u001B[0m")))
        assertEquals(listOf("red"), run.session?.lines?.toList())
        chunks.trySend(RunChunk(2, (1..KEPT_LINES + 49).map { "line $it" }))
        val session = assertNotNull(run.session)
        assertEquals(KEPT_LINES, session.lines.size)
        assertEquals(50, session.droppedLines)
        assertEquals("line ${KEPT_LINES + 49}", session.lines.last())
    }

    @Test
    fun `a colored line keeps its colors while the lines before it are dropped, and loses them with it`() {
        val run = state()
        run.add("prog")
        run.runSelected()
        val chunks = runner.started[0].chunks
        chunks.trySend(RunChunk(1, listOf("plain", "\u001B[31mred\u001B[0m")))
        val session = assertNotNull(run.session)
        val red = session.spansAt(1)
        assertEquals(1, red.size)
        assertTrue(session.spansAt(0).isEmpty())
        chunks.trySend(RunChunk(2, (1..<KEPT_LINES).map { "line $it" }))
        assertEquals(1, session.droppedLines)
        assertEquals("red", session.lines[0])
        assertEquals(red, session.spansAt(0))
        chunks.trySend(RunChunk(3, listOf("last")))
        assertEquals("line 1", session.lines[0])
        assertTrue(session.spansAt(0).isEmpty())
        assertTrue(session.spansAt(KEPT_LINES - 1).isEmpty())
    }

    @Test
    fun `only a test configuration reads the output into results`() {
        val run = state()
        run.add("tests", RunKind.Tests)
        run.runSelected()
        runner.started[0].chunks.trySend(RunChunk(1, listOf("Started A", "Started t()", "Passed t()")))
        assertEquals(1, run.session?.results?.size)
        assertEquals(RunTab.Tests, run.tab)
        run.add("program", RunKind.Run)
        run.runSelected()
        runner.started[1].chunks.trySend(RunChunk(1, listOf("Started A", "Started t()", "Passed t()")))
        assertTrue(run.session?.results?.isEmpty() == true)
        assertEquals(RunTab.Output, run.tab)
    }

    @Test
    fun `a program written with dot slash is looked up in the working directory`() {
        val run = state(root = "/work/p")
        run.add("./kotlin test -m core")
        run.runSelected()
        assertEquals("/work/p", runner.started[0].directory)
        assertEquals(listOf("/work/p/kotlin", "test", "-m", "core"), runner.started[0].command)
    }

    @Test
    fun `the working directory is relative to the project, on a Windows server too`() {
        val run = state(root = """C:\work\p""")
        run.add("""..\gradlew.bat test""".removePrefix("."), directory = "app")
        run.runSelected()
        assertEquals("""C:\work\p\app""", runner.started[0].directory)
        assertEquals(listOf("""C:\work\p\app\gradlew.bat""", "test"), runner.started[0].command)
    }

    @Test
    fun `a program on the path is left as it is`() {
        val run = state()
        run.add("java -version")
        run.runSelected()
        assertEquals(listOf("java", "-version"), runner.started[0].command)
    }

    @Test
    fun `a server that cannot run commands says so`() {
        val run = state(projectRunner = FakeRunner(available = false))
        run.add("prog")
        run.runSelected()
        assertEquals(RunStatus.NotStarted, run.session?.status)
        assertEquals("This server cannot run commands", run.session?.error)
    }

    @Test
    fun `a blank command does not start`() {
        val run = state()
        run.add("   ")
        run.runSelected()
        assertEquals(RunStatus.NotStarted, run.session?.status)
        assertTrue(runner.started.isEmpty())
    }

    @Test
    fun `running with nothing selected does nothing`() {
        val run = state()
        run.runSelected()
        assertNull(run.session)
    }

    @Test
    fun `saving selects the configuration, and a blank name becomes the command`() {
        val run = state()
        val first = run.add("one")
        run.save(RunConfiguration("other", "  ", RunKind.Run, "two words"))
        assertEquals("other", run.selectedId)
        assertEquals("two words", run.selected?.name)
        run.save(first.copy(name = "Renamed"))
        assertEquals(listOf("Renamed", "two words"), run.configurations.map { it.name })
        assertEquals(first.id, run.selectedId)
    }

    @Test
    fun `deleting the selected configuration selects the first one left`() {
        val run = state()
        val first = run.add("one")
        val second = run.add("two")
        run.delete(second.id)
        assertEquals(first.id, run.selectedId)
        run.delete(first.id)
        assertNull(run.selected)
        assertTrue(run.configurations.isEmpty())
    }

    @Test
    fun `configurations and selection are restored from the preferences`() {
        val first = state(root = "/p")
        val kept = first.add("one two")
        first.add("three")
        first.select(kept.id)
        val saved = first.toPreferences()
        val restored = state(root = "/p", preferences = saved)
        assertEquals(first.configurations.toList(), restored.configurations.toList())
        assertEquals(kept.id, restored.selectedId)
        assertTrue(state(root = "/other", preferences = saved).configurations.isEmpty())
    }

    @Test
    fun `configurations found in the project are listed after the saved ones, and are not saved`() {
        val found =
            listOf(
                RunConfiguration("composer:api:test", "Composer: test", RunKind.Run, "composer run-script test"),
            )
        val run = state(found = found)
        val own = run.add("mine")
        run.ensureLoaded()
        assertEquals(listOf(own.id, "composer:api:test"), run.available.map { it.id })
        assertEquals(listOf(own.id), run.configurations.map { it.id })
        run.select("composer:api:test")
        assertEquals("Composer: test", run.selected?.name)
        assertEquals(listOf(own.id), state(preferences = run.toPreferences()).configurations.map { it.id })
    }

    @Test
    fun `configurations are grouped by folder, the project first`() {
        val found =
            listOf(
                RunConfiguration("w", "Web", RunKind.Run, "x", "services/web"),
                RunConfiguration("a", "Api", RunKind.Run, "x", "services/api/"),
                RunConfiguration("r", "Root", RunKind.Run, "x"),
            )
        val run = state(found = found)
        val own = run.add("mine", directory = "services/api")
        run.ensureLoaded()
        val folders = run.byFolder.map { (folder, configurations) -> folder to configurations.map { it.id } }
        assertEquals(
            listOf("" to listOf("r"), "services/api" to listOf(own.id, "a"), "services/web" to listOf("w")),
            folders,
        )
    }

    @Test
    fun `the first configuration found is selected when nothing is`() {
        val found = listOf(RunConfiguration("s", "Found", RunKind.Tests, "./kotlin test"))
        val run = state(found = found)
        run.ensureLoaded()
        assertEquals("s", run.selectedId)
        assertTrue(run.configurations.isEmpty())
    }

    @Test
    fun `a saved copy of a configuration found takes its place in the list`() {
        val found = RunConfiguration("s", "Found", RunKind.Run, "tool")
        val run = state(found = listOf(found))
        run.ensureLoaded()
        run.save(found.copy(name = "Mine", commandLine = "tool --more"))
        assertEquals(listOf("Mine"), run.available.map { it.name })
    }

    @Test
    fun `nothing is saved before the configurations were read`() {
        assertTrue(state().toPreferences().isEmpty())
    }

    @Test
    fun `a link opens the file it names at its line and column`() {
        val entry = FileEntry("/work/p/A.kt", "A.kt", false)
        val run = state(located = entry)
        run.open(OutputLink(0, 1, "A.kt", 8, 5))
        assertEquals(listOf(Triple(entry, 8, 5)), opened)
    }

    @Test
    fun `a link to a file the project does not have opens nothing`() {
        val run = state(located = null)
        run.open(OutputLink(0, 1, "Nowhere.kt", 8, 5))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the commands run last come first, once each, and are kept in the preferences`() {
        val run = state()
        val first = run.add("one")
        val second = run.add("two")
        run.run(first)
        run.run(second)
        run.run(first)
        assertEquals(listOf(first.id, second.id), run.recent.map { it.id })
        val saved = run.toPreferences()
        val reopened = state(preferences = saved)
        assertEquals(listOf(first.id, second.id), reopened.recent.map { it.id })
    }

    @Test
    fun `only a few recent commands are kept, and a deleted one is not listed`() {
        val run = state()
        val all = (1..8).map { run.add("cmd$it") }
        all.forEach { run.run(it) }
        assertEquals(6, run.recent.size)
        assertEquals(all.last().id, run.recent.firstOrNull()?.id)
        run.delete(all.last().id)
        assertEquals(5, run.recent.size)
    }

    @Test
    fun `the search matches every word in the name, command or folder`() {
        val run = state()
        run.add("npm run dev", directory = "web")
        run.add("composer run-script test", directory = "api")
        assertEquals(2, run.search("  ").size)
        assertEquals(listOf("npm run dev"), run.search("WEB dev").map { it.commandLine })
        assertEquals(listOf("composer run-script test"), run.search("script").map { it.commandLine })
        assertTrue(run.search("nothing like it").isEmpty())
        assertEquals(listOf("api"), run.byFolder("composer").map { it.first })
    }

    @Test
    fun `a command is running while its session is`() {
        val run = state()
        val configuration = run.add("prog")
        val other = run.add("other")
        assertFalse(run.isRunning(configuration.id))
        run.run(configuration)
        assertTrue(run.isRunning(configuration.id))
        assertFalse(run.isRunning(other.id))
        runner.started[0].chunks.trySend(RunChunk(1, emptyList(), finished = true, exitCode = 0))
        assertFalse(run.isRunning(configuration.id))
    }

    @Test
    fun `the recent commands are grouped by folder, the most recent folder first`() {
        val run = state()
        val web = run.add("npm run dev", directory = "web")
        val api = run.add("composer run-script test", directory = "api")
        val build = run.add("npm run build", directory = "web")
        run.run(web)
        run.run(api)
        run.run(build)
        val grouped = run.recentByFolder.map { (folder, list) -> folder to list.map { it.id } }
        assertEquals(listOf("web" to listOf(build.id, web.id), "api" to listOf(api.id)), grouped)
    }

    @Test
    fun `a discovered configuration that is deleted stays hidden, saved in the preferences`() {
        val found = RunConfiguration("package:web:dev", "npm: dev", RunKind.Run, "npm run dev", "web")
        val other = RunConfiguration("package:web:build", "npm: build", RunKind.Run, "npm run build", "web")
        val run = state(found = listOf(found, other))
        run.ensureLoaded()
        assertEquals(2, run.available.size)
        run.delete(found.id)
        assertEquals(listOf(other.id), run.available.map { it.id })
        val reopened = state(preferences = run.toPreferences(), found = listOf(found, other))
        reopened.ensureLoaded()
        assertEquals(listOf(other.id), reopened.available.map { it.id })
    }

    @Test
    fun `any command of the list can be edited, and saving it brings a hidden one back`() {
        val found = RunConfiguration("package:dev", "npm: dev", RunKind.Run, "npm run dev")
        val run = state(found = listOf(found))
        run.ensureLoaded()
        run.edit(found.id)
        assertEquals(found.id, run.editing?.id)
        run.delete(found.id)
        assertTrue(run.available.isEmpty())
        run.save(found.copy(name = "Dev server"))
        assertEquals(listOf("Dev server"), run.available.map { it.name })
    }

    @Test
    fun `the folder filter limits the search and the recent commands to one folder`() {
        val run = state()
        val web = run.add("npm run dev", directory = "web")
        val api = run.add("composer run-script test", directory = "api")
        run.run(web)
        run.run(api)
        assertEquals(listOf("api", "web"), run.folders.sorted())
        run.toggleFolder("web")
        assertEquals(listOf(web.id), run.search("").map { it.id })
        assertEquals(listOf("web"), run.byFolder("").map { it.first })
        assertEquals(listOf("web"), run.recentByFolder.map { it.first })
        assertTrue(run.search("composer").isEmpty())
        run.selectAllFolders()
        assertEquals(setOf("web", "api"), run.folderFilter)
        assertEquals(2, run.search("").size)
        run.clearFolderFilter()
        assertEquals(2, run.search("").size)
        assertTrue(run.folderFilter.isEmpty())
    }

    @Test
    fun `the folders can be searched, and select all checks the ones the search leaves`() {
        val run = state()
        listOf("web/app", "web/admin", "api", "").forEach { run.run(run.add("cmd", directory = it)) }
        assertEquals(listOf("web/admin", "web/app"), run.searchFolders("WEB").sorted())
        assertEquals(listOf("web/admin"), run.searchFolders("web adm"))
        assertEquals(listOf(""), run.searchFolders("proj"))
        assertEquals(4, run.searchFolders("  ").size)
        run.selectAllFolders(run.searchFolders("web"))
        assertEquals(setOf("web/app", "web/admin"), run.folderFilter)
        run.selectAllFolders()
        assertEquals(4, run.folderFilter.size)
    }
}
