package org.example.kotlineditor.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.documents.OutOfSyncException
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WAIT_MS = 60_000L

/** Documents and completion through the service, against a real fake-server process. */
class DocumentSyncTest {
    private val project = createTempDirectory("lsp-project")
    private val data = createTempDirectory("lsp-data")
    private val file = project.resolve("index.php").toString()
    private var options = arrayOf<String>()
    private val service by lazy {
        val fake =
            LanguageServerCandidate(
                id = "fake",
                displayName = "fake",
                language = "php",
                licence = "Test",
                strengths = emptyList(),
                drawbacks = emptyList(),
                requirements = emptyList(),
                tool = null,
                command = { _, _ -> FakeLanguageServerCommand.command(*options) },
            )
        val nothing =
            RuntimeDetector(
                pathEnvironment = "",
                extraDirectories = { emptyList() },
                probe = { _, _ -> null },
            )
        Files.writeString(data.resolve("language-servers.properties"), "php=fake\n")
        LanguageServerService(
            LanguageServerSettings(
                dataDirectory = data,
                toolsDirectory = data.resolve("tools"),
                candidates = CandidateRegistry(nothing, listOf(fake)),
                timings = SupervisorTimings(idleStopMs = 500, initializeTimeoutMs = 20_000, firstBackoffMs = 100),
                completionTimeoutMs = 5_000,
            ),
        )
    }

    @AfterTest
    fun cleanup() {
        service.close()
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    private fun running(): Boolean =
        runBlocking { service.status(-1) }.servers.any { it.state == LanguageServerState.Running }

    private fun awaitRunning() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!running()) {
            check(System.currentTimeMillis() < deadline) { "No server running" }
            Thread.sleep(20)
        }
    }

    // A server just started may not have the documents yet: completion is retried until it is ready.
    private fun complete(version: Int, line: Int, column: Int): CompletionResult =
        runBlocking {
            withTimeout(WAIT_MS) {
                var result = service.complete("s1", file, version, line, column)
                while (!result.ready) {
                    delay(50)
                    result = service.complete("s1", file, version, line, column)
                }
                result
            }
        }

    @Test
    fun `completion proposes the functions and variables of the edited text`() {
        service.use("s1", project.toString(), listOf(file))
        service.openDocument("s1", project.toString(), file, 1, "<?php\nfunction greet(\$name) {}\n")
        awaitRunning()
        // Typed after the server started: it only knows them through didChange.
        service.changeDocument("s1", file, 2, listOf(TextChange(2, 0, 2, 0, "function farewell() {}\n\$total = 1;\n")))
        service.changeDocument("s1", file, 3, listOf(TextChange(4, 0, 4, 0, "gr")))
        val functions = complete(3, 4, 2)
        assertEquals(listOf("greet"), functions.items.map { it.label })
        assertEquals(0, functions.replaceFrom)

        service.changeDocument("s1", file, 4, listOf(TextChange(4, 0, 4, 2, "\$to")))
        val variables = complete(4, 4, 3)
        assertEquals(listOf("\$total"), variables.items.map { it.label })
        assertEquals(0, variables.replaceFrom)
    }

    @Test
    fun `documents opened before the server started are sent when it is ready`() {
        service.openDocument("s1", project.toString(), file, 1, "<?php\nfunction early() {}\nea")
        service.use("s1", project.toString(), listOf(file))
        awaitRunning()
        assertEquals(listOf("early"), complete(1, 2, 2).items.map { it.label })
    }

    @Test
    fun `a restarted server gets the documents again`() {
        service.use("s1", project.toString(), listOf(file))
        service.openDocument("s1", project.toString(), file, 1, "<?php\nfunction before() {}\n")
        awaitRunning()
        val server = runBlocking { service.status(-1) }.servers.single()
        service.restart(server.id, server.generation)
        // Edited while the server restarts: the new process gets the text as it is now.
        service.changeDocument("s1", file, 2, listOf(TextChange(2, 0, 2, 0, "function after() {}\nbe")))
        awaitRunning()
        assertEquals(listOf("before"), complete(2, 3, 2).items.map { it.label })
        service.changeDocument("s1", file, 3, listOf(TextChange(3, 0, 3, 2, "af")))
        assertEquals(listOf("after"), complete(3, 3, 2).items.map { it.label })
    }

    @Test
    fun `a server that wants the whole text gets it`() {
        options = arrayOf("--full-sync")
        service.use("s1", project.toString(), listOf(file))
        service.openDocument("s1", project.toString(), file, 1, "<?php\n")
        awaitRunning()
        service.changeDocument("s1", file, 2, listOf(TextChange(1, 0, 1, 0, "function whole() {}\nwh")))
        assertEquals(listOf("whole"), complete(2, 2, 2).items.map { it.label })
    }

    @Test
    fun `no server, no file, or a stale version answer not ready`() {
        runBlocking {
            assertFalse(service.complete("s1", file, 1, 0, 0).ready, "not open")
            service.openDocument("s1", project.toString(), file, 1, "<?php\n")
            assertFalse(service.complete("s1", file, 1, 0, 0).ready, "no server")
        }
        assertFailsWith<OutOfSyncException> { service.changeDocument("s1", file, 5, emptyList()) }
        service.openDocument("s1", project.toString(), project.resolve("README.md").toString(), 1, "# x")
        assertTrue(runBlocking { !service.complete("s1", project.resolve("README.md").toString(), 1, 0, 0).ready })
    }

    @Test
    fun `a refused completion proposes nothing, without failing`() {
        options = arrayOf("--no-completion")
        service.use("s1", project.toString(), listOf(file))
        service.openDocument("s1", project.toString(), file, 1, "<?php\nx")
        awaitRunning()
        val result = complete(1, 1, 1)
        assertTrue(result.items.isEmpty())
    }
}
