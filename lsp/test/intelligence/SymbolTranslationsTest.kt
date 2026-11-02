package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.DeclarationKind
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolTranslationsTest {
    // Absolute on every platform (a drive on Windows), named as the IDE server names it.
    private val file = Paths.get("/tmp/project/src/A.php").toAbsolutePath()
    private val uri = file.toUri().toString()
    private val path = file.toString()

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun range(line: Int, start: Int, end: Int) =
        """{"start":{"line":$line,"character":$start},"end":{"line":$line,"character":$end}}"""

    @Test
    fun `a hierarchical outline is flattened in document order, skipping namespaces but keeping their children`() {
        val method = """{"name":"greet","kind":6,"range":${range(4, 4, 30)},"selectionRange":${range(4, 20, 25)}}"""
        val property = """{"name":"name","kind":7,"range":${range(3, 4, 20)},"selectionRange":${range(3, 12, 16)}}"""
        val type =
            """{"name":"Greeter","kind":5,"range":${range(2, 0, 40)},"selectionRange":${range(2, 6, 13)},""" +
                """"children":[$method,$property]}"""
        val namespace =
            """{"name":"App","kind":3,"range":${range(1, 0, 50)},"selectionRange":${range(1, 10, 13)},""" +
                """"children":[$type]}"""
        val outline = outlineOf(json("[$namespace]"))
        val expected =
            listOf(
                Declaration("Greeter", DeclarationKind.Class, 2, 6, 0),
                Declaration("name", DeclarationKind.Property, 3, 12, 1),
                Declaration("greet", DeclarationKind.Function, 4, 20, 1),
            )
        assertEquals(expected, outline)
    }

    @Test
    fun `a flat list of SymbolInformations nests the members under their container`() {
        val type = """{"name":"Greeter","kind":5,"location":{"uri":"$uri","range":${range(2, 6, 13)}}}"""
        val method =
            """{"name":"greet","kind":6,"containerName":"Greeter",""" +
                """"location":{"uri":"$uri","range":${range(4, 20, 25)}}}"""
        val outline = outlineOf(json("[$method,$type]"))
        assertEquals(listOf(0, 1), outline.map { it.depth })
        assertEquals(listOf("Greeter", "greet"), outline.map { it.name })
    }

    @Test
    fun `variables and unnamed symbols are not listed, and a missing answer is no declaration`() {
        val variable = """{"name":"${'$'}x","kind":13,"range":${range(1, 0, 2)},"selectionRange":${range(1, 0, 2)}}"""
        val unnamed = """{"name":"","kind":12,"range":${range(2, 0, 2)},"selectionRange":${range(2, 0, 2)}}"""
        assertEquals(emptyList(), outlineOf(json("[$variable,$unnamed]")))
        assertEquals(emptyList(), outlineOf(null))
    }

    @Test
    fun `workspace symbols keep the file ones with their container, a WorkspaceSymbol without range at its top`() {
        val method =
            """{"name":"greet","kind":6,"containerName":"Greeter",""" +
                """"location":{"uri":"$uri","range":${range(4, 20, 25)}}}"""
        val withoutRange = """{"name":"Polite","kind":11,"location":{"uri":"$uri"}}"""
        val inPhar = """{"name":"Vendor","kind":5,"location":{"uri":"phar:///x.phar/V.php","range":${range(
            0,
            0,
            1,
        )}}}"""
        val symbols = workspaceSymbolsOf(json("[$method,$withoutRange,$inPhar]"))
        assertEquals(listOf("greet", "Polite"), symbols.map { it.declaration.name })
        assertEquals(listOf(path, path), symbols.map { it.path })
        assertEquals("Greeter", symbols.firstOrNull()?.container)
        assertEquals(DeclarationKind.Interface, symbols.getOrNull(1)?.declaration?.kind)
        assertEquals(0, symbols.getOrNull(1)?.declaration?.line)
    }

    private fun span(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        """{"start":{"line":$startLine,"character":$startColumn},"end":{"line":$endLine,"character":$endColumn}}"""

    @Test
    fun `selection ranges are the first range and its parents, innermost first`() {
        val file = """{"range":${span(0, 0, 9, 0)}}"""
        val method = """{"range":${span(2, 4, 5, 5)},"parent":$file}"""
        val word = """{"range":${range(3, 15, 17)},"parent":$method}"""
        val other = """{"range":${range(7, 0, 1)}}"""
        val expected =
            listOf(
                TextRange(3, 15, 3, 17),
                TextRange(2, 4, 5, 5),
                TextRange(0, 0, 9, 0),
            )
        assertEquals(expected, selectionRangesOf(json("[$word,$other]")))
    }

    @Test
    fun `a parent equal to its child or not holding it is skipped, and no answer gives no range`() {
        val outer = """{"range":${span(0, 0, 9, 0)}}"""
        val elsewhere = """{"range":${range(8, 0, 3)},"parent":$outer}"""
        val same = """{"range":${range(3, 15, 17)},"parent":$elsewhere}"""
        val word = """{"range":${range(3, 15, 17)},"parent":$same}"""
        assertEquals(listOf(TextRange(3, 15, 3, 17), TextRange(0, 0, 9, 0)), selectionRangesOf(json("[$word]")))
        assertEquals(emptyList(), selectionRangesOf(json("null")))
        assertEquals(emptyList(), selectionRangesOf(json("[]")))
    }

    @Test
    fun `folding ranges keep the multi-line ones, the longest of those starting on the same line`() {
        val answer =
            """[{"startLine":4,"endLine":6,"kind":"imports"},{"startLine":8,"endLine":11},""" +
                """{"startLine":8,"endLine":9},{"startLine":3,"endLine":3},{"endLine":5},{"startLine":12,"endLine":10}]"""
        assertEquals(mapOf(4 to 6, 8 to 11), foldingRegionsOf(json(answer)))
        assertEquals(emptyMap(), foldingRegionsOf(json("null")))
    }
}
