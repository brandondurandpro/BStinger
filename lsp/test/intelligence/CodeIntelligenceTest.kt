package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.HighlightKind
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_MS = 60_000L
private const val SESSION = "s1"

private val GREETER =
    """
    <?php

    /**
     * Greets people politely.
     */
    class Greeter
    {
        /**
         * Says hello to someone.
         *
         * @param string ${'$'}name Who to greet.
         * @return string The greeting.
         */
        public function greet(string ${'$'}name, int ${'$'}times): string
        {
            return "Hello " . strtoupper(${'$'}name);
        }
    }
    """.trimIndent()

private val MAIN =
    """
    <?php
    ${'$'}greeter = new Greeter();
    echo ${'$'}greeter->greet("world", 1);
    echo ${'$'}greeter->greet(strlen("x"), 2);
    missing_function();
    """.trimIndent()

/** Hover, navigation, usages, diagnostics and edits through the service, against a real fake-server process. */
class CodeIntelligenceTest {
    private val project = createTempDirectory("lsp-intelligence")
    private val data = createTempDirectory("lsp-intelligence-data")
    private val greeter = project.resolve("Greeter.php").toString()
    private val main = project.resolve("main.php").toString()
    private var options = arrayOf<String>()
    private var indexingWaitMs = 60_000L
    private var indexingStartMs = 500L
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
                timings = supervisorTimings(),
                intelligence = IntelligenceSettings(timeoutMs = 5_000, holdMs = 2_000, indexingWaitMs = indexingWaitMs),
            ),
        )
    }
    private val intelligence get() = service.intelligence

    private fun supervisorTimings() =
        SupervisorTimings(
            idleStopMs = 500,
            initializeTimeoutMs = 20_000,
            firstBackoffMs = 100,
            indexingStartMs = indexingStartMs,
        )

    @AfterTest
    fun cleanup() {
        service.close()
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    @Test
    fun `usages and declarations wait for a server indexing the project, which finds none before`() {
        options = arrayOf("--progress", "2", "--blind-until-indexed")
        openMain()
        // No retry: the first answer is the one after the indexing.
        val usages = runBlocking { intelligence.references(SESSION, position(2, 18), includeDeclaration = false) }
        assertEquals(FeatureStatus.Ready, usages.status)
        assertEquals(listOf(main to 2, main to 3), usages.locations.map { it.path to it.range.startLine })
        val declaration = runBlocking { intelligence.navigate(SESSION, position(1, 17), NavigationKind.Definition) }
        assertEquals(listOf(greeter), declaration.locations.map { it.path })
    }

    @Test
    fun `usage counts are counted once the server indexed the project`() {
        options = arrayOf("--progress", "2", "--blind-until-indexed")
        Files.writeString(Paths.get(main), MAIN)
        service.use(SESSION, project.toString(), listOf(greeter))
        service.openDocument(SESSION, project.toString(), greeter, 1, GREETER)
        awaitRunning()
        val counts = runBlocking { intelligence.usageCounts(SESSION, greeter, 1) }
        assertEquals(listOf("Greeter" to 1, "greet" to 2), counts.counts.map { it.name to it.count })
    }

    @Test
    fun `a server indexing longer than the wait is not ready, rather than finding no usage`() {
        options = arrayOf("--progress", "30", "--blind-until-indexed")
        // A slow runner may begin the indexing late (the fake waits 200 ms): the IDE must still be waiting for it.
        indexingStartMs = 5_000
        indexingWaitMs = 6_000
        openMain()
        val started = System.nanoTime()
        val usages = runBlocking { intelligence.references(SESSION, position(2, 18), includeDeclaration = false) }
        val waited = (System.nanoTime() - started) / 1_000_000
        assertEquals(FeatureStatus.NotReady, usages.status)
        assertTrue(waited in 900..60_000, "waited $waited ms")
    }

    // Greeter.php only on disk, main.php open: the server finds the class by reading the project.
    private fun openMain(text: String = MAIN) {
        Files.writeString(Paths.get(greeter), GREETER)
        Files.writeString(Paths.get(main), text)
        service.use(SESSION, project.toString(), listOf(main))
        service.openDocument(SESSION, project.toString(), main, 1, text)
        awaitRunning()
    }

    private fun awaitRunning() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (runBlocking { service.status(-1) }.servers.none { it.state == LanguageServerState.Running }) {
            check(System.currentTimeMillis() < deadline) { "No server running" }
            Thread.sleep(20)
        }
    }

    // Retried until the server that just started has the documents.
    private fun <T> ready(status: (T) -> FeatureStatus, ask: suspend () -> T): T =
        runBlocking {
            withTimeout(WAIT_MS) {
                var result = ask()
                while (status(result) == FeatureStatus.NotReady) {
                    delay(50)
                    result = ask()
                }
                result
            }
        }

    private fun position(line: Int, column: Int, path: String = main, version: Int = 1) =
        DocumentPosition(path, version, line, column)

    @Test
    fun `hover shows the docblock and the signature of a method declared in a file that is not open`() {
        openMain()
        val hover = ready({ it.status }) { intelligence.hover(SESSION, position(2, 18)) }
        val markdown = assertNotNull(hover.markdown)
        assertTrue(markdown.startsWith("### Greeter # greet"), markdown)
        assertTrue("Says hello to someone." in markdown, markdown)
        assertTrue("- **\$name**: *string* Who to greet." in markdown, markdown)
        assertTrue("```php\n<?php public function greet(string \$name, int \$times): string\n```" in markdown, markdown)
        assertEquals(TextRange(2, 15, 2, 20), hover.range)
        val nothing = runBlocking { intelligence.hover(SESSION, position(0, 0)) }
        assertEquals(FeatureStatus.Ready, nothing.status)
        assertNull(nothing.markdown)
    }

    @Test
    fun `definition, type definition and implementations go to the declarations, with a preview`() {
        openMain()
        val definition =
            ready(
                { it.status },
            ) { intelligence.navigate(SESSION, position(2, 18), NavigationKind.Definition) }
        val location = definition.locations.single()
        assertEquals(greeter, location.path)
        assertEquals(13, location.range.startLine)
        assertEquals("    public function greet(string \$name, int \$times): string", location.preview)
        val type = runBlocking { intelligence.navigate(SESSION, position(2, 7), NavigationKind.TypeDefinition) }
        assertEquals(TextRange(5, 6, 5, 13), type.locations.single().range)
        val implementations =
            runBlocking {
                intelligence.navigate(
                    SESSION,
                    position(2, 7),
                    NavigationKind.Implementation,
                )
            }
        assertEquals(FeatureStatus.Ready, implementations.status)
        assertTrue(implementations.locations.isEmpty())
    }

    @Test
    fun `references find the usages in every file, without the declaration unless asked`() {
        openMain()
        val usages =
            ready(
                { it.status },
            ) { intelligence.references(SESSION, position(2, 18), includeDeclaration = false) }
        assertEquals(listOf(main to 2, main to 3), usages.locations.map { it.path to it.range.startLine })
        assertEquals("echo \$greeter->greet(\"world\", 1);", usages.locations.first().preview)
        val withDeclaration =
            runBlocking {
                intelligence.references(
                    SESSION,
                    position(2, 18),
                    includeDeclaration = true,
                )
            }
        assertEquals(3, withDeclaration.locations.size)
    }

    @Test
    fun `usage counts give each declaration of a document its number of usages`() {
        Files.writeString(Paths.get(main), MAIN)
        service.use(SESSION, project.toString(), listOf(greeter))
        service.openDocument(SESSION, project.toString(), greeter, 1, GREETER)
        awaitRunning()
        val counts = ready({ it.status }) { intelligence.usageCounts(SESSION, greeter, 1) }
        assertEquals(listOf("Greeter" to 1, "greet" to 2), counts.counts.map { it.name to it.count })
        assertEquals(TextRange(13, 20, 13, 25), counts.counts[1].range)
    }

    @Test
    fun `diagnostics follow the edits of the document`() {
        openMain()
        val first =
            runBlocking {
                withTimeout(WAIT_MS) {
                    var snapshot = intelligence.diagnostics(SESSION, 0)
                    while (snapshot.files.flatMap { it.diagnostics }.isEmpty()) {
                        snapshot =
                            intelligence.diagnostics(
                                SESSION,
                                snapshot.version,
                            )
                    }
                    snapshot
                }
            }
        val problem =
            first.files
                .single { it.path == main }
                .diagnostics
                .single()
        assertEquals("Function \"missing_function\" not found", problem.message)
        assertEquals(DiagnosticSeverity.Error, problem.severity)
        assertEquals(TextRange(4, 0, 4, 16), problem.range)
        // The call is removed: the next snapshot has no problem left.
        service.changeDocument(SESSION, main, 2, listOf(TextChange(4, 0, 4, 19, "")))
        val fixed =
            runBlocking {
                withTimeout(WAIT_MS) {
                    var snapshot = intelligence.diagnostics(SESSION, first.version)
                    while (snapshot.files.flatMap { it.diagnostics }.isNotEmpty()) {
                        snapshot =
                            intelligence.diagnostics(
                                SESSION,
                                snapshot.version,
                            )
                    }
                    snapshot
                }
            }
        assertEquals(listOf(main), fixed.files.map { it.path })
        service.closeDocument(SESSION, main)
        assertTrue(runBlocking { intelligence.diagnostics(SESSION, -1) }.files.isEmpty())
    }

    @Test
    fun `rename edits every file, the open one named as the client names it`() {
        openMain()
        val target = ready({ it.status }) { intelligence.prepareRename(SESSION, position(2, 18)) }
        assertEquals(TextRange(2, 15, 2, 20), target.range)
        assertEquals("greet", target.placeholder)
        val edit = runBlocking { intelligence.rename(SESSION, position(2, 18), "welcome") }
        assertEquals(setOf(main, greeter), edit.changes.map { it.path }.toSet())
        val mainEdits = edit.changes.single { it.path == main }.edits
        assertEquals(2, mainEdits.size)
        assertTrue(edit.changes.flatMap { it.edits }.all { it.text == "welcome" })
        val nothing = runBlocking { intelligence.prepareRename(SESSION, position(1, 12)) }
        assertNull(nothing.range)
    }

    @Test
    fun `formatting is unsupported when the server does not offer it`() {
        options = arrayOf("--without", "formatting,rangeFormatting")
        openMain(php("\techo 1;   "))
        val result = ready({ it.status }) { intelligence.format(SESSION, main, 1, null, FormatOptions()) }
        assertEquals(FeatureStatus.Unsupported, result.status)
    }

    @Test
    fun `formatting edits the lines that change`() {
        openMain(php("\techo 1;   ", "echo 2;"))
        val result = ready({ it.status }) { intelligence.format(SESSION, main, 1, null, FormatOptions()) }
        assertEquals(listOf(TextChange(1, 0, 1, 11, "    echo 1;")), result.changes.single().edits)
        val range = runBlocking { intelligence.format(SESSION, main, 1, TextRange(2, 0, 2, 7), FormatOptions()) }
        assertTrue(range.changes.isEmpty())
    }

    @Test
    fun `code actions apply an edit, or run a command whose edits come back to the client`() {
        openMain(php("missing_function();", "function documented() {}"))
        // The diagnostics are what the server fixes: wait for them first.
        runBlocking {
            withTimeout(WAIT_MS) {
                var snapshot = intelligence.diagnostics(SESSION, 0)
                while (snapshot.files.flatMap { it.diagnostics }.isEmpty()) {
                    snapshot =
                        intelligence.diagnostics(
                            SESSION,
                            snapshot.version,
                        )
                }
            }
        }
        val fixes = ready({ it.status }) { intelligence.codeActions(SESSION, main, 1, TextRange(1, 3, 1, 3)) }
        assertEquals(listOf("Create function missing_function"), fixes.actions.map { it.title })
        assertTrue(fixes.actions.single().preferred)
        val created = runBlocking { intelligence.applyCodeAction(SESSION, fixes.token, 0) }
        assertEquals(
            listOf("", "function missing_function()", "{", "}", "").joinToString("\n"),
            created.changes
                .single()
                .edits
                .single()
                .text,
        )

        val refactorings = runBlocking { intelligence.codeActions(SESSION, main, 1, TextRange(2, 0, 2, 0)) }
        assertEquals(listOf("Add docblock"), refactorings.actions.map { it.title })
        val docblock = runBlocking { intelligence.applyCodeAction(SESSION, refactorings.token, 0) }
        assertEquals(
            TextChange(2, 0, 2, 0, listOf("/**", " * TODO", " */", "").joinToString("\n")),
            docblock.changes
                .single()
                .edits
                .single(),
        )
        assertEquals(main, docblock.changes.single().path)
        // A list replaced by a newer one cannot be applied any more.
        val stale = runBlocking { intelligence.applyCodeAction(SESSION, fixes.token, 0) }
        assertTrue(stale.changes.isEmpty())
        assertNotNull(stale.message)
    }

    @Test
    fun `signature help and highlights`() {
        openMain()
        val help = ready({ it.status }) { intelligence.signatureHelp(SESSION, position(2, 31)) }
        val signature = help.signatures.single()
        assertEquals("greet(string \$name, int \$times): string", signature.label)
        assertEquals(1, help.activeParameter)
        assertEquals(
            "int \$times",
            signature.label.substring(signature.parameters[1].start, signature.parameters[1].end),
        )
        val highlights = runBlocking { intelligence.highlights(SESSION, position(2, 7)) }
        assertEquals(listOf(HighlightKind.Write, HighlightKind.Read, HighlightKind.Read), highlights.map { it.kind })
    }

    @Test
    fun `nothing is ready without a server, nor for a file no server handles`() {
        runBlocking {
            assertEquals(FeatureStatus.NotReady, intelligence.hover(SESSION, position(0, 0)).status)
            service.openDocument(SESSION, project.toString(), main, 1, "<?php\n")
            assertEquals(
                FeatureStatus.NotReady,
                intelligence.navigate(SESSION, position(0, 0), NavigationKind.Definition).status,
            )
            assertEquals(
                FeatureStatus.NotReady,
                intelligence.references(SESSION, position(0, 0, version = 2), true).status,
            )
        }
    }

    // The diagnostics of the session until [done] holds.
    private fun diagnosticsUntil(since: Long, done: (DiagnosticsSnapshot) -> Boolean): DiagnosticsSnapshot =
        runBlocking {
            withTimeout(WAIT_MS) {
                var snapshot = intelligence.diagnostics(SESSION, since)
                while (!done(snapshot)) snapshot = intelligence.diagnostics(SESSION, snapshot.version)
                snapshot
            }
        }

    @Test
    fun `diagnostics a server gives when asked are pulled after each edit, and again when it asks`() {
        options = arrayOf("--pull-diagnostics")
        openMain()
        val first = diagnosticsUntil(0) { snapshot -> snapshot.files.any { it.diagnostics.isNotEmpty() } }
        val problem =
            first.files
                .firstOrNull { it.path == main }
                ?.diagnostics
                ?.singleOrNull()
        assertEquals("Function \"missing_function\" not found", problem?.message)
        // Another call of the unknown function, typed: pulled again.
        service.changeDocument(SESSION, main, 2, listOf(TextChange(4, 0, 4, 0, "missing_function();\n")))
        val twice = diagnosticsUntil(first.version) { snapshot -> snapshot.files.sumOf { it.diagnostics.size } == 2 }
        // The function is declared in a new file: the server asks for its diagnostics to be pulled again.
        val declared = project.resolve("missing.php")
        Files.writeString(declared, php("function missing_function()", "{", "}"))
        service.diskChanged(listOf(declared.toString() to DiskChange.Created))
        diagnosticsUntil(twice.version) { snapshot -> snapshot.files.all { it.diagnostics.isEmpty() } }
    }
}

// A PHP file of [lines], after its opening tag.
private fun php(vararg lines: String): String = (listOf("<?php") + lines + "").joinToString("\n")
