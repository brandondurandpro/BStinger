package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CodeLensAction
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyKind
import org.example.kotlineditor.model.InlayHintKind
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
import kotlin.test.assertTrue

private const val WAIT_MS = 60_000L
private const val SESSION = "s1"

private val POLITE = listOf("<?php", "interface Polite", "{", "}", "").joinToString("\n")

private val GREETER =
    listOf(
        "<?php",
        "class Greeter implements Polite",
        "{",
        "    public function greet(string \$name, int \$times): string",
        "    {",
        "        return \"Hello \" . \$name;",
        "    }",
        "}",
        "",
    ).joinToString("\n")

// `shout` is declared at line 3 and called at line 7; greet is called at line 2.
private val MAIN =
    listOf(
        "<?php",
        "\$greeter = new Greeter();",
        "echo \$greeter->greet(\"world\", 1);",
        "function shout(string \$text)",
        "{",
        "    return strtoupper(\$text);",
        "}",
        "echo shout(\"hi\");",
        "",
    ).joinToString("\n")

/**
 * Outline, symbols, smart selection, folding, inlay hints, call and type hierarchies and completion documentation
 * through the service, against a real fake-server process.
 */
class CodeStructureIntegrationTest {
    private val project = createTempDirectory("lsp-structure")
    private val data = createTempDirectory("lsp-structure-data")
    private val main = project.resolve("main.php").toString()
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
                intelligence = IntelligenceSettings(timeoutMs = 5_000, holdMs = 2_000),
            ),
        )
    }
    private val intelligence get() = service.intelligence

    @AfterTest
    fun cleanup() {
        service.close()
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    // Greeter.php and Polite.php only on disk, main.php open.
    private fun openMain() {
        Files.writeString(project.resolve("Polite.php"), POLITE)
        Files.writeString(project.resolve("Greeter.php"), GREETER)
        Files.writeString(Paths.get(main), MAIN)
        service.use(SESSION, project.toString(), listOf(main))
        service.openDocument(SESSION, project.toString(), main, 1, MAIN)
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

    private fun position(line: Int, column: Int) = DocumentPosition(main, 1, line, column)

    @Test
    fun `outline and workspace symbols name the declarations`() {
        openMain()
        val outline = ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        assertEquals(listOf("shout"), outline.declarations.map { it.name })
        assertEquals(3, outline.declarations.single().line)
        val symbols = ready({ it.status }) { intelligence.workspaceSymbols(SESSION, "gree") }
        val names = symbols.symbols.map { it.declaration.name }.toSet()
        assertEquals(setOf("Greeter", "greet"), names)
        val greet = symbols.symbols.firstOrNull { it.declaration.name == "greet" }
        assertEquals("Greeter", greet?.container)
    }

    @Test
    fun `selection ranges grow from the word to the file, and folding gives the body of the function`() {
        openMain()
        val selection = ready({ it.status }) { intelligence.selectionRanges(SESSION, position(5, 13)) }
        assertEquals(TextRange(5, 11, 5, 21), selection.ranges.firstOrNull())
        selection.ranges.zipWithNext().forEach { (inner, outer) ->
            val holds = outer.startLine <= inner.startLine && inner.endLine <= outer.endLine
            assertTrue(holds && inner != outer, "$outer does not hold $inner")
        }
        val folding = ready({ it.status }) { intelligence.foldingRanges(SESSION, main, 1) }
        assertEquals(6, folding.regions[3])
    }

    @Test
    fun `inlay hints name the parameters before the arguments of known functions`() {
        openMain()
        val hints = ready({ it.status }) { intelligence.inlayHints(SESSION, main, 1) }.hints
        val shown = hints.map { Triple(it.line, it.column, it.label) }
        assertEquals(listOf(Triple(2, 21, "name: "), Triple(2, 30, "times: "), Triple(7, 11, "text: ")), shown)
        assertTrue(hints.all { it.kind == InlayHintKind.Parameter })
    }

    @Test
    fun `the call hierarchy gives the callers and the callees, the top of a file calling like a function`() {
        openMain()
        val prepared =
            ready(
                { it.status },
            ) { intelligence.prepareHierarchy(SESSION, position(2, 17), HierarchyKind.Calls) }
        val greet = prepared.items.single()
        assertEquals("greet", greet.name)
        assertTrue(greet.location.path.endsWith("Greeter.php"))
        assertEquals(TextRange(3, 20, 3, 25), greet.location.range)
        val callers = runBlocking { intelligence.hierarchyChildren(main, greet, HierarchyDirection.Callers) }
        assertEquals(FeatureStatus.Ready, callers.status)
        val caller = callers.items.single()
        assertEquals("main.php", caller.name)
        // A caller goes to its call, with the text of its line.
        assertEquals(TextRange(2, 15, 2, 20), caller.location.range)
        assertEquals("echo \$greeter->greet(\"world\", 1);", caller.location.preview)
        val shout = runBlocking { intelligence.prepareHierarchy(SESSION, position(3, 10), HierarchyKind.Calls) }
        val callees =
            runBlocking {
                intelligence.hierarchyChildren(
                    main,
                    shout.items.single(),
                    HierarchyDirection.Callees,
                )
            }
        // strtoupper is declared nowhere in the project.
        assertEquals(FeatureStatus.Ready, callees.status)
        assertEquals(emptyList(), callees.items)
    }

    @Test
    fun `the type hierarchy gives the interfaces a class implements, and the classes implementing them`() {
        openMain()
        val prepared =
            ready(
                { it.status },
            ) { intelligence.prepareHierarchy(SESSION, position(1, 17), HierarchyKind.Types) }
        val greeter = prepared.items.single()
        assertEquals("Greeter", greeter.name)
        val supertypes = runBlocking { intelligence.hierarchyChildren(main, greeter, HierarchyDirection.Supertypes) }
        val polite = supertypes.items.single()
        assertEquals("Polite", polite.name)
        val subtypes = runBlocking { intelligence.hierarchyChildren(main, polite, HierarchyDirection.Subtypes) }
        assertEquals(listOf("Greeter"), subtypes.items.map { it.name })
        // Nothing at the caret: no root.
        val none = runBlocking { intelligence.prepareHierarchy(SESSION, position(0, 1), HierarchyKind.Types) }
        assertEquals(FeatureStatus.Ready, none.status)
        assertEquals(emptyList(), none.items)
    }

    @Test
    fun `semantic tokens are named with the server's legend, in the order of the text`() {
        openMain()
        val tokens = ready({ it.status }) { intelligence.semanticTokens(SESSION, main, 1) }.tokens
        val shown = tokens.map { "${it.line}:${it.column}:${it.length}:${it.type}:${it.modifiers.sorted()}" }
        // `$greeter = new Greeter();`, then `echo $greeter->greet("world", 1);`.
        assertTrue("1:0:8:variable:[]" in shown, "$shown")
        assertTrue("1:15:7:class:[]" in shown, "$shown")
        assertTrue("2:15:5:method:[]" in shown, "$shown")
        // `function shout(string $text)`, then its use in the body.
        assertTrue("3:9:5:function:[declaration]" in shown, "$shown")
        assertTrue("3:22:5:parameter:[]" in shown, "$shown")
        assertTrue("5:22:5:parameter:[]" in shown, "$shown")
        assertEquals(tokens.sortedWith(compareBy({ it.line }, { it.column })), tokens)
    }

    @Test
    fun `a server without definitions is asked for the declaration instead`() {
        options = arrayOf("--without", "definition")
        openMain()
        val found =
            ready({ it.status }) { intelligence.navigate(SESSION, position(2, 17), NavigationKind.Definition) }
        val greet = found.locations.single()
        assertTrue(greet.path.endsWith("Greeter.php"), greet.path)
        assertEquals(3, greet.range.startLine)
    }

    @Test
    fun `without definitions nor declarations, going to the declaration is unsupported`() {
        options = arrayOf("--without", "definition,declaration")
        openMain()
        ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        val found = runBlocking { intelligence.navigate(SESSION, position(2, 17), NavigationKind.Definition) }
        assertEquals(FeatureStatus.Unsupported, found.status)
    }

    @Test
    fun `features the server does not offer are unsupported, not waited for`() {
        options =
            arrayOf(
                "--without",
                "inlayHint,callHierarchy,typeHierarchy,foldingRange,selectionRange,semanticTokens",
            )
        openMain()
        // The outline answers once the server has the document: the others are then known to be missing.
        ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        runBlocking {
            assertEquals(FeatureStatus.Unsupported, intelligence.inlayHints(SESSION, main, 1).status)
            assertEquals(FeatureStatus.Unsupported, intelligence.semanticTokens(SESSION, main, 1).status)
            assertEquals(FeatureStatus.Unsupported, intelligence.foldingRanges(SESSION, main, 1).status)
            assertEquals(FeatureStatus.Unsupported, intelligence.selectionRanges(SESSION, position(5, 13)).status)
            val calls = intelligence.prepareHierarchy(SESSION, position(3, 10), HierarchyKind.Calls)
            assertEquals(FeatureStatus.Unsupported, calls.status)
            val types = intelligence.prepareHierarchy(SESSION, position(1, 17), HierarchyKind.Types)
            assertEquals(FeatureStatus.Unsupported, types.status)
        }
    }

    @Test
    fun `a completion item of a declared function resolves to its documentation`() {
        openMain()
        val result =
            runBlocking {
                withTimeout(WAIT_MS) {
                    var answer = service.complete(SESSION, main, 1, 7, 8)
                    while (!answer.ready) {
                        delay(50)
                        answer = service.complete(SESSION, main, 1, 7, 8)
                    }
                    answer
                }
            }
        val shout = result.items.firstOrNull { it.label == "shout" }
        assertNotNull(shout, "${result.items.map { it.label }}")
        val documentation = runBlocking { service.resolveCompletion(SESSION, result.token, shout.resolveIndex) }
        assertTrue(documentation.orEmpty().contains("function shout(string \$text)"), documentation)
    }

    @Test
    fun `code lenses are resolved, and running one gives the edits its command asked for`() {
        openMain()
        val result = ready({ it.status }) { intelligence.codeLenses(SESSION, main, 1) }
        // `shout` has no docblock: its lens came without a command, and was resolved.
        val lens = result.lenses.singleOrNull()
        assertEquals("Add docblock", lens?.title)
        assertEquals(CodeLensAction.Run, lens?.action)
        assertEquals(3, lens?.range?.startLine)
        val edit = runBlocking { intelligence.runCodeLens(SESSION, result.token, 0) }
        assertEquals(FeatureStatus.Ready, edit.status)
        val change = edit.changes.singleOrNull()
        assertEquals(main, change?.path)
        assertEquals(listOf("/**", " * TODO", " */", "").joinToString("\n"), change?.edits?.singleOrNull()?.text)
        // A list replaced by a newer one is gone.
        val newer = runBlocking { intelligence.codeLenses(SESSION, main, 1) }
        val stale = runBlocking { intelligence.runCodeLens(SESSION, result.token, 0) }
        assertEquals("This lens is no longer available", stale.message)
        assertTrue(newer.token != result.token)
    }

    @Test
    fun `the implementations lens of an interface lists the classes implementing it`() {
        openMain()
        val polite = project.resolve("Polite.php").toString()
        service.openDocument(SESSION, project.toString(), polite, 1, POLITE)
        val result = ready({ it.status }) { intelligence.codeLenses(SESSION, polite, 1) }
        val lens = result.lenses.singleOrNull()
        assertEquals("1 implementation", lens?.title)
        assertEquals(CodeLensAction.ShowLocations, lens?.action)
        val location = lens?.locations?.singleOrNull()
        assertEquals(project.resolve("Greeter.php").toString(), location?.path)
        assertEquals("class Greeter implements Polite", location?.preview)
    }

    @Test
    fun `without code lenses the server says so`() {
        options = arrayOf("--without", "codeLens")
        openMain()
        ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        val result = runBlocking { intelligence.codeLenses(SESSION, main, 1) }
        assertEquals(FeatureStatus.Unsupported, result.status)
        val run = runBlocking { intelligence.runCodeLens(SESSION, "unknown", 0) }
        assertEquals("This lens is no longer available", run.message)
    }

    @Test
    fun `document links give the required files once resolved, and the web addresses at once`() {
        val app = project.resolve("app.php").toString()
        val text =
            listOf(
                "<?php",
                "// https://example.com/docs",
                "require __DIR__ . '/Greeter.php';",
                "",
            ).joinToString("\n")
        Files.writeString(Paths.get(app), text)
        openMain()
        service.openDocument(SESSION, project.toString(), app, 1, text)
        val links = ready({ it.status }) { intelligence.documentLinks(SESSION, app, 1) }.links
        assertEquals(listOf(1, 2), links.map { it.range.startLine })
        assertEquals("https://example.com/docs", links.firstOrNull()?.url)
        val required = links.getOrNull(1)
        assertEquals(project.resolve("Greeter.php").toString(), required?.path)
        assertEquals("Open the required file", required?.tooltip)
    }

    @Test
    fun `without document links the server says so`() {
        options = arrayOf("--without", "documentLink")
        openMain()
        ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        val result = runBlocking { intelligence.documentLinks(SESSION, main, 1) }
        assertEquals(FeatureStatus.Unsupported, result.status)
    }

    @Test
    fun `typing a trigger character gives the server's edits, another character none`() {
        openMain()
        val triggers = ready({ it.status }) { intelligence.typeFormattingTriggers(SESSION, main, 1) }
        assertEquals(listOf(";", "}"), triggers.characters)
        val text = MAIN.replace("echo shout(\"hi\");", "echo shout(\"hi\")  ;")
        service.changeDocument(SESSION, main, 2, listOf(TextChange(0, 0, MAIN.lines().lastIndex, 0, text)))
        val after = DocumentPosition(main, 2, 7, "echo shout(\"hi\")  ;".length)
        val edit = runBlocking { intelligence.formatOnType(SESSION, after, ";", FormatOptions()) }
        val change =
            edit.changes
                .singleOrNull()
                ?.edits
                ?.singleOrNull()
        assertEquals(TextChange(7, 16, 7, 18, ""), change)
        val other = runBlocking { intelligence.formatOnType(SESSION, after, "x", FormatOptions()) }
        assertEquals(FeatureStatus.Ready, other.status)
        assertTrue(other.changes.isEmpty())
    }

    @Test
    fun `without formatting while typing the server says so`() {
        options = arrayOf("--without", "onTypeFormatting")
        openMain()
        ready({ it.status }) { intelligence.outline(SESSION, main, 1) }
        val triggers = runBlocking { intelligence.typeFormattingTriggers(SESSION, main, 1) }
        assertEquals(FeatureStatus.Unsupported, triggers.status)
        val edit = runBlocking { intelligence.formatOnType(SESSION, position(7, 5), ";", FormatOptions()) }
        assertEquals(FeatureStatus.Unsupported, edit.status)
    }
}
