package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** XML files are colored by tree-sitter, with the HTML grammar (the catalog has no XML one). */
class XmlHighlightingTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    private val lines =
        listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""",
            "  <!-- the dependencies -->",
            """  <dependency scope="test"/>""",
            "  <name>Hello &amp; goodbye</name>",
            "  <script><![CDATA[ a < b ]]></script>",
            "</project>",
        )

    @AfterTest
    fun close() = repository.close()

    private fun colored(): Map<Int, LineSpans> {
        val spec = assertNotNull(GrammarSpec.forFileName("pom.xml"))
        val grammar = assertNotNull(runBlocking { store.await(spec) }, store.lastFailure)
        TreeSitterHighlighter(grammar).use { highlighter ->
            highlighter.update(lines)
            return highlighter.highlight(0, lines.size)
        }
    }

    // The text a line has colored, by the style of the color.
    private fun coloredText(spans: Map<Int, LineSpans>, line: Int): List<String> {
        val own = spans[line] ?: return emptyList()
        return (0 until own.size).map { lines[line].substring(own.starts[it], own.ends[it]) }
    }

    @Test
    fun `the whole XML family reaches the grammar of tree-sitter`() {
        for (name in listOf("pom.xml", "schema.xsd", "style.xsl", "logo.svg", "Info.plist", "app.csproj", "a.fxml")) {
            assertEquals("html", GrammarSpec.forFileName(name)?.id, name)
        }
    }

    @Test
    fun `tags, attributes, values and comments of an XML file are colored`() {
        val spans = colored()
        assertTrue("project" in coloredText(spans, 1), "the tag name of line 1: ${spans[1]}")
        assertTrue("xmlns" in coloredText(spans, 1), "the attribute of line 1: ${spans[1]}")
        assertTrue(coloredText(spans, 2).any { "dependencies" in it }, "the comment: ${spans[2]}")
        assertTrue("dependency" in coloredText(spans, 3), "the self-closing tag: ${spans[3]}")
        assertTrue("scope" in coloredText(spans, 3), "its attribute: ${spans[3]}")
        assertTrue("name" in coloredText(spans, 4), "an element with text: ${spans[4]}")
        assertTrue("project" in coloredText(spans, 6), "the closing tag: ${spans[6]}")
    }
}
