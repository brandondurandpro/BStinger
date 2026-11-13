package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.highlightChunks
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChunkHighlightingTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    @AfterTest
    fun close() = repository.close()

    private fun styled(spans: LineSpans, line: String): Map<String, Int> =
        (0 until spans.size).associate { line.substring(spans.starts[it], spans.ends[it]) to spans.styles[it] }

    @Test
    fun `a chunk is colored the same after other chunks as alone`() {
        val old = listOf("fun total(a: Int): Int {", "    return a", "}")
        val new = listOf("val answer = compute(42)", "val other = 1")
        val together = runBlocking { store.highlightChunks("App.kt", listOf(old, new)) }
        val alone = runBlocking { store.highlightChunks("App.kt", listOf(new)) }
        val both = assertNotNull(together)
        val single = assertNotNull(alone)
        new.indices.forEach { line ->
            assertEquals(styled(single[0][line], new[line]), styled(both[1][line], new[line]))
        }
        assertEquals(CaptureStyles.styleOf("keyword"), styled(both[1][0], new[0])["val"])
    }
}
