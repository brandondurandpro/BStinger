package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.InjectedHighlighters
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The code a `.vue` file holds besides its markup is colored by the grammar of its own language. */
class VueInjectionTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    private val lines =
        listOf(
            "<script setup lang='ts'>",
            "import { ref } from 'vue'",
            "const n: number = 1",
            "</script>",
            "<template>",
            "  <p :title=\"label\">{{ n + 2 }}</p>",
            "</template>",
            "<style>",
            "p { color: red; }",
            "</style>",
        )

    @AfterTest
    fun close() = repository.close()

    private fun colored(): Map<Int, LineSpans> {
        val spec = GrammarSpec.all.single { it.id == "vue" }
        val grammar = assertNotNull(runBlocking { store.await(spec) }, store.lastFailure)
        // Injected grammars are not waited for by the highlighter: they are loaded beforehand.
        for (id in listOf("typescript", "css")) {
            val injectedSpec = GrammarSpec.all.single { it.id == id }
            runBlocking { store.await(injectedSpec) }
        }
        TreeSitterHighlighter(grammar).use { highlighter ->
            InjectedHighlighters(store).use { injected ->
                highlighter.update(lines)
                val own = highlighter.highlight(0, lines.size)
                val parts = highlighter.injections()
                return runBlocking { injected.overlay(own, lines, parts, 0 until lines.size) }
            }
        }
    }

    private fun styled(spans: Map<Int, LineSpans>, index: Int): Map<String, Int> {
        val line = lines[index]
        val found = spans[index]
        val count = found?.size ?: 0
        return (0 until count).associate { line.substring(found!!.starts[it], found.ends[it]) to found.styles[it] }
    }

    private fun style(name: String) = CaptureStyles.styleOf(name)

    @Test
    fun `the script is colored as TypeScript`() {
        val spans = colored()
        assertEquals(style("keyword"), styled(spans, 1)["import"])
        assertEquals(style("keyword"), styled(spans, 2)["const"])
        assertEquals(style("string"), styled(spans, 1)["'vue'"])
        assertEquals(style("number"), styled(spans, 2)["1"])
    }

    @Test
    fun `the expressions of the template are colored, and the markup keeps its own colors`() {
        val spans = colored()
        assertEquals(style("number"), styled(spans, 5)["2"])
        assertNull(styled(spans, 5)[";"], "the separator of the expressions is not colored")
    }

    @Test
    fun `the style is colored as CSS`() {
        val spans = colored()
        assertNotNull(spans[8]?.takeIf { it.size > 0 }, "the CSS line has colors")
    }
}
