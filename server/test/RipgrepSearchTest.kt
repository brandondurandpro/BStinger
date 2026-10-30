package org.example.kotlineditor.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepQuery
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Find in Files with the real ripgrep binary, and the fallback to the built-in search. */
class RipgrepSearchTest {
    private val root: Path = Files.createTempDirectory("ripgrep-search").toRealPath()
    private val started = CopyOnWriteArrayList<ProcessHandle>()
    private val project get() = FileEntry(root.toString(), root.fileName.toString(), true)

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun write(relative: String, text: String) {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun search(executable: String?, timeout: Duration = 30.seconds): RipgrepSearch {
        val settings = RipgrepSettings(executable = executable, bundledDirectory = null, download = false)
        return RipgrepSearch(
            LocalSearch(LocalFileSystem()),
            RipgrepProvisioner(settings),
            TextFiles.DEFAULT_MAX_SIZE,
            timeout,
        ) { started += it }
    }

    private fun search(binary: RipgrepBinary) = search(binary.path.toString())

    private fun List<TextMatch>.relative() =
        map { "${root.relativize(Path.of(it.entry.path)).toString().replace('\\', '/')}:${it.line}:${it.column}" }

    // Every ripgrep started by the test has exited (it was killed or it finished).
    private fun assertAllEnded() {
        assertTrue(started.isNotEmpty(), "ripgrep was not started")
        for (process in started) {
            runCatching { process.onExit().get(10, TimeUnit.SECONDS) }
            assertFalse(process.isAlive, "ripgrep ${process.pid()} still running")
        }
    }

    @Test
    fun `finds text with or without case, with character columns and stable order`() =
        runBlocking {
            val rg = TestRipgrep.require()
            write("b.kt", "val x = 1\n// Needle here\n")
            write("a/日本.txt", listOf("日本語 needle", "no", "NEEDLE again").joinToString("\r\n"))
            val search = search(rg)
            assertEquals(RipgrepBinary.Origin.Configured, search.ripgrep()?.origin)

            val all = search.findInFiles(project, "needle", ignoreCase = true, limit = 100)
            assertEquals(listOf("a/日本.txt:0:4", "a/日本.txt:2:0", "b.kt:1:3"), all.relative())
            assertEquals("日本語 needle", all[0].lineText)
            assertEquals("日本.txt", all[0].entry.name)

            val exact = search.findInFiles(project, "needle", ignoreCase = false, limit = 100)
            assertEquals(listOf("a/日本.txt:0:4"), exact.relative())
            assertAllEnded()
        }

    @Test
    fun `honours gitignore and skips VCS and build directories but not other hidden files`() =
        runBlocking {
            val rg = TestRipgrep.require()
            root.resolve(".git").createDirectories()
            write(".gitignore", "generated/\n*.log\n")
            write("generated/a.txt", "needle")
            write("app.log", "needle")
            write(".git/config", "needle")
            write("module/build/out.txt", "needle")
            write("node_modules/x/index.js", "needle")
            write(".github/workflow.yml", "needle")
            write("src/Main.kt", "needle")
            val hits = search(rg).findInFiles(project, "needle", ignoreCase = false, limit = 100)
            assertEquals(listOf(".github/workflow.yml:0:0", "src/Main.kt:0:0"), hits.relative())
        }

    @Test
    fun `a query that looks like an option is searched literally`() =
        runBlocking {
            val rg = TestRipgrep.require()
            write("a.txt", "run with --files please\n(a+b)*\n")
            write("b.txt", """say "hi there" \"""")
            val search = search(rg)
            assertEquals(listOf("a.txt:0:9"), search.findInFiles(project, "--files", false, 10).relative())
            assertEquals(listOf("a.txt:1:0"), search.findInFiles(project, "(a+b)*", false, 10).relative())
            // Quotes and backslashes reach ripgrep intact (Windows' command-line quoting would mangle them).
            assertEquals(listOf("b.txt:0:4"), search.findInFiles(project, """"hi there" \"""", false, 10).relative())
        }

    @Test
    fun `stops at the limit and kills ripgrep`() =
        runBlocking {
            val rg = TestRipgrep.require()
            repeat(20) { file -> write("f$file.txt", "needle line\n".repeat(5_000)) }
            val hits = search(rg).findInFiles(project, "needle", ignoreCase = false, limit = 25)
            assertEquals(25, hits.size)
            assertAllEnded()
        }

    @Test
    fun `cancelling the search kills ripgrep`() =
        runBlocking {
            val rg = TestRipgrep.require()
            repeat(20) { file -> write("f$file.txt", "needle line\n".repeat(5_000)) }
            val runner = RipgrepRunner(rg.path, TextFiles.DEFAULT_MAX_SIZE) { started += it }
            val first = CompletableDeferred<Unit>()
            val job =
                launch {
                    // A collector that never asks for more: ripgrep ends up blocked on its full output pipe.
                    runner.matches(RipgrepQuery(root, "needle", ignoreCase = false)).collect {
                        first.complete(Unit)
                        awaitCancellation()
                    }
                }
            first.await()
            assertTrue(started.single().isAlive, "ripgrep should still be running")
            job.cancelAndJoin()
            assertAllEnded()
        }

    @Test
    fun `a search past its timeout returns what it found and kills ripgrep`() =
        runBlocking {
            val rg = TestRipgrep.require()
            repeat(50) { file -> write("f$file.txt", "needle line\n".repeat(5_000)) }
            val search = search(rg.path.toString(), timeout = 1.milliseconds)
            val hits = search.findInFiles(project, "needle", ignoreCase = false, limit = 1_000_000)
            assertTrue(hits.size < 250_000)
            started.forEach { it.onExit().get(10, TimeUnit.SECONDS) }
        }

    @Test
    fun `killing ripgrep also kills the processes it started`() {
        if (isWindows) return
        val process = ProcessBuilder("sh", "-c", "sleep 60 & sleep 60 & wait").start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (process.descendants().count() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        val children = process.descendants().toList()
        assertEquals(2, children.size)
        RipgrepRunner.destroyTree(process)
        for (handle in children + process.toHandle()) {
            runCatching { handle.onExit().get(10, TimeUnit.SECONDS) }
            assertFalse(handle.isAlive)
        }
    }

    @Test
    fun `falls back to the built-in search when the configured ripgrep does not exist`() =
        runBlocking {
            write("a.txt", "x needle\n")
            val search = search(root.resolve("missing/rg").toString())
            assertNull(search.ripgrep())
            assertEquals(listOf("a.txt:0:2"), search.findInFiles(project, "needle", false, 10).relative())
            assertTrue(started.isEmpty())
        }

    @Test
    fun `falls back to the built-in search when ripgrep fails`() {
        if (isWindows) return
        // Passes for ripgrep (`--version`) but fails every search, like a broken or incompatible build.
        val fake = root.resolve("tools/rg")
        fake.parent.createDirectories()
        fake.writeText(
            """
            #!/bin/sh
            if [ "$1" = --version ]; then echo 'ripgrep 0.0.0'; exit 0; fi
            exit 2
            """.trimIndent(),
        )
        fake.toFile().setExecutable(true)
        write("src/a.txt", "x needle\n")
        runBlocking {
            val search = search(fake.toString())
            assertEquals(fake, search.ripgrep()?.path)
            val hits = search.findInFiles(project, "needle", false, 10)
            assertEquals(listOf("src/a.txt:0:2"), hits.relative())
        }
        assertAllEnded()
    }

    private companion object {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
    }
}
