package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.TextRange
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What real servers send, translated for the IDE. */
class LspTranslationsTest {
    private val file = Paths.get("/tmp/project/a.php").toAbsolutePath()
    private val uri = file.toUri().toString()

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun range(a: Int, b: Int, c: Int, d: Int) = """{"start":{"line":$a,"character":$b},"end":{"line":$c,"character":$d}}"""

    @Test
    fun `locations come as one Location, an array, or LocationLinks, and phar URIs are left out`() {
        val one = locationsOf(json("""{"uri":"$uri","range":${range(1, 2, 3, 4)}}"""))
        assertEquals(listOf(file.toString() to TextRange(1, 2, 3, 4)), one.map { it.path to it.range })
        val links =
            locationsOf(
                json(
                    """[{"targetUri":"$uri","targetRange":${range(0, 0, 9, 1)},"targetSelectionRange":${range(2, 6, 2, 13)}},
                       {"uri":"phar:///x.phar/stubs.php","range":${range(1, 0, 1, 1)}}]""",
                ),
            )
        assertEquals(listOf(TextRange(2, 6, 2, 13)), links.map { it.range })
        assertTrue(locationsOf(json("null")).isEmpty())
    }

    @Test
    fun `hover contents of every shape become Markdown`() {
        assertEquals("# Title", hoverMarkdown(json("""{"contents":{"kind":"markdown","value":"# Title"}}""")))
        assertEquals("a\\*b  \nc", hoverMarkdown(json("""{"contents":{"kind":"plaintext","value":"a*b\nc"}}""")))
        assertEquals(
            "```php\nfunction f()\n```\n\n---\n\nDoes things",
            hoverMarkdown(json("""{"contents":[{"language":"php","value":"function f()"},"Does things"]}""")),
        )
        assertNull(hoverMarkdown(json("""{"contents":""}""")))
        assertNull(hoverMarkdown(json("null")))
    }

    @Test
    fun `workspace edits by URI or document changes, merged by file, other file operations refused`() {
        val changes = workspaceEditOf(json("""{"changes":{"$uri":[{"range":${range(0, 0, 0, 1)},"newText":"x"}]}}"""))!!
        assertEquals(1, changes.single().edits.size)
        val documentChanges =
            workspaceEditOf(
                json(
                    """{"documentChanges":[
                        {"kind":"create","uri":"$uri"},
                        {"textDocument":{"uri":"$uri","version":null},"edits":[{"range":${range(0, 0, 0, 0)},"newText":"<?php"}]}]}""",
                ),
            )!!
        assertTrue(documentChanges.single().create)
        assertEquals("<?php", documentChanges.single().edits.single().text)
        assertNull(workspaceEditOf(json("""{"documentChanges":[{"kind":"rename","oldUri":"$uri","newUri":"$uri"}]}""")))
    }

    @Test
    fun `signature parameters as labels or offsets`() {
        val help =
            signatureHelpOf(
                json(
                    """{"signatures":[{"label":"f(int ${'$'}a, int ${'$'}b)","parameters":[{"label":"int ${'$'}a"},{"label":[9,15]}]}],
                        "activeSignature":0,"activeParameter":1}""",
                ),
            )
        val parameters = help.signatures.single().parameters
        assertEquals(listOf(2 to 8, 9 to 15), parameters.map { it.start to it.end })
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun `document symbols are flattened, only the declarations whose usages count`() {
        val symbols =
            countedSymbolsOf(
                json(
                    """[{"name":"Greeter","kind":5,"range":${range(0, 0, 9, 1)},"selectionRange":${range(0, 6, 0, 13)},
                        "children":[{"name":"greet","kind":6,"range":${range(2, 4, 4, 5)},"selectionRange":${range(2, 20, 2, 25)}},
                                    {"name":"${'$'}local","kind":13,"range":${range(3, 0, 3, 1)},"selectionRange":${range(3, 0, 3, 1)}}]}]""",
                ),
            )
        assertEquals(listOf("Greeter", "greet"), symbols.map { it.name })
    }

    @Test
    fun `diagnostics keep their severity and tags, and code actions put the preferred fixes first`() {
        val diagnostics =
            diagnosticsOf(
                json(
                    """{"uri":"$uri","diagnostics":[
                        {"range":${range(3, 0, 3, 2)},"severity":2,"message":"unused","tags":[1]},
                        {"range":${range(1, 0, 1, 2)},"message":"  boom  ","code":42}]}""",
                ) as JsonObject,
            )
        assertEquals(listOf("boom", "unused"), diagnostics.map { it.message })
        assertEquals("42", diagnostics.first().code)
        assertTrue(diagnostics.last().unnecessary)
        val actions =
            codeActionsOf(
                json(
                    """[{"title":"Extract","kind":"refactor.extract"},{"title":"Import","kind":"quickfix.import","isPreferred":true},
                        {"title":"Disabled","disabled":{"reason":"no"}},{"title":"Run","command":"run"}]""",
                ),
            )
        assertEquals(listOf("Import", "Extract", "Run"), actions.map { it.first.title })
    }
}
