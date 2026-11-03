package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.Snippet
import org.example.kotlineditor.syntax.treesitter.highlightSnippets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetHighlightingTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    @AfterTest
    fun close() = repository.close()

    private fun styled(spans: LineSpans?, line: String): Map<String, Int> {
        val colored = spans ?: return emptyMap()
        return (0 until colored.size).associate {
            line.substring(
                colored.starts[it],
                colored.ends[it],
            ) to colored.styles[it]
        }
    }

    private fun style(name: String) = CaptureStyles.styleOf(name)

    @Test
    fun `a PHP line is colored as code without its opening tag`() {
        val line = "\$greeter->greet(\"world\", 1);"
        val spans = runBlocking { store.highlightSnippets(listOf(Snippet("index.php", line))) }
        val colors = styled(spans.single(), line)
        assertEquals(style("function"), colors["greet"])
        assertEquals(style("string"), colors["\"world\""])
        assertEquals(style("number"), colors["1"])
    }

    @Test
    fun `each line is parsed alone, so an unclosed comment does not color the next one`() {
        val lines = listOf("/* starts a comment", "val answer = compute(42)")
        val spans = runBlocking { store.highlightSnippets(lines.map { Snippet("App.kt", it) }) }
        val second = styled(spans[1], lines[1])
        assertEquals(style("keyword"), second["val"])
        assertEquals(style("function"), second["compute"])
    }

    @Test
    fun `lines of several languages keep their order, a file without a grammar has no colors`() {
        val snippets =
            listOf(
                Snippet("a.php", "echo greet();"),
                Snippet("notes.txt", "greet() is called here"),
                Snippet("B.kt", "greet()"),
            )
        val spans = runBlocking { store.highlightSnippets(snippets) }
        assertEquals(3, spans.size)
        assertEquals(style("function"), styled(spans[0], snippets[0].text)["greet"])
        assertNull(spans[1])
        assertEquals(style("function"), styled(spans[2], snippets[2].text)["greet"])
        assertEquals(2, repository.jarsServed, "one jar per language: PHP and Kotlin")
    }
}
