package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.NativeMemory
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureMemory
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.LoadedGrammar
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import java.lang.foreign.ValueLayout
import java.lang.management.ManagementFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureMemoryTest {
    @Test
    fun `the block is used again from its start after a recycle`() {
        CaptureMemory().use { memory ->
            val first = memory.allocate(32, 8)
            val second = memory.allocate(32, 8)
            assertEquals(first.address() + 32, second.address())
            memory.recycle()
            assertEquals(first.address(), memory.allocate(32, 8).address())
        }
    }

    @Test
    fun `what is allocated is aligned and zeroed, even where the block was written`() {
        CaptureMemory().use { memory ->
            memory.allocate(4, 4).set(ValueLayout.JAVA_INT, 0, -1)
            val aligned = memory.allocate(8, 8)
            assertEquals(0L, aligned.address() % 8)
            aligned.set(ValueLayout.JAVA_LONG, 0, -1L)
            memory.recycle()
            assertEquals(0, memory.allocate(4, 4).get(ValueLayout.JAVA_INT, 0))
            assertEquals(0L, memory.allocate(8, 8).get(ValueLayout.JAVA_LONG, 0))
        }
    }

    @Test
    fun `what does not fit the block is allocated apart and stays valid`() {
        CaptureMemory(blockBytes = 64).use { memory ->
            val inBlock = memory.allocate(48, 8)
            val beyond = memory.allocate(32, 8)
            val larger = memory.allocate(1_024, 8)
            inBlock.set(ValueLayout.JAVA_LONG, 40, 1L)
            beyond.set(ValueLayout.JAVA_LONG, 24, 2L)
            larger.set(ValueLayout.JAVA_LONG, 1_016, 3L)
            assertEquals(1L, inBlock.get(ValueLayout.JAVA_LONG, 40))
            assertEquals(2L, beyond.get(ValueLayout.JAVA_LONG, 24))
            assertEquals(3L, larger.get(ValueLayout.JAVA_LONG, 1_016))
            assertNotEquals(inBlock.address() + 48, beyond.address())
        }
    }
}

class HighlightMemoryTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    @AfterTest
    fun close() = repository.close()

    private fun grammar(id: String = "kotlin"): LoadedGrammar =
        assertNotNull(runBlocking { store.await(GrammarSpec.all.single { it.id == id }) }, store.lastFailure)

    // A bundle: short lines, and minified ones of [longCharacters] characters.
    private fun bundle(longLines: Int, longCharacters: Int): List<String> {
        val statement = "function f(a,b){return a+b*2}var s='text',n=42;"
        val minified = statement.repeat(longCharacters / statement.length)
        return (0 until longLines).flatMap { listOf("// chunk $it", minified, "const loaded$it = true") }
    }

    /**
     * The lines on screen of a minified file: the query ran over their million characters, hundreds of thousands
     * of captures for colors that stop at the first ten thousand, again for each screen scrolled to.
     */
    @Test
    fun `the colors of minified lines cost their first characters, not their length`() {
        val lines = bundle(longLines = 12, longCharacters = 400_000)
        TreeSitterHighlighter(grammar("typescript")).use { highlighter ->
            highlighter.update(lines)
            highlighter.highlight(0, 3)
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val before = threads.currentThreadAllocatedBytes
            val start = System.nanoTime()
            val spans = highlighter.highlight(0, lines.size)
            val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
            val megabytes = (threads.currentThreadAllocatedBytes - before) / BYTES_PER_MB
            println("PERF ${"colors of a screen of minified lines".padEnd(50)} $millis ms, $megabytes MB allocated")
            val long = assertNotNull(spans[1])
            assertTrue(long.size > 0, "the start of a minified line is colored")
            assertTrue(long.ends.all { it <= MAX_COLORED }, "a minified line is colored beyond its start")
            // The lines after a minified one are colored as in a file without it.
            assertTrue(spans[2].list().isNotEmpty(), "the line after a minified one is colored")
            assertEquals(spans[2].list(), spans[5].list())
            assertTrue(megabytes <= SCREEN_BUDGET_MB, "a screen of minified lines allocated $megabytes MB")
        }
    }

    // A file of `functions` functions of 8 lines, each line with a dozen captures.
    private fun largeFile(functions: Int): List<String> =
        (0 until functions).flatMap { n ->
            listOf(
                "/** The function number $n. */",
                "fun compute$n(first: Int, second: String = \"none\"): List<Int> {",
                "    val total = first + second.length * $n // a comment",
                "    if (total > 10 && second.isNotEmpty()) return listOf(total, first, $n)",
                "    val text = \"value \$total of \${second.trim()}\"",
                "    return text.map { it.code }.filter { it != 32 }",
                "}",
                "",
            )
        }

    @Test
    fun `a large file is colored like its lines asked for one screen at a time`() {
        val lines = largeFile(functions = 500)
        TreeSitterHighlighter(grammar()).use { highlighter ->
            highlighter.update(lines)
            val whole = highlighter.highlight(0, lines.size)
            val screens = (lines.indices step 40).flatMap { from -> highlighter.highlight(from, from + 40).entries }
            assertEquals(lines.size, screens.size)
            for ((line, spans) in screens) assertEquals(whole[line].list(), spans.list(), "line $line")
            assertTrue(whole.values.sumOf { it.size } > lines.size, "the file has colors")
        }
    }

    private fun LineSpans?.list(): List<Triple<Int, Int, Int>> {
        val spans = this ?: return emptyList()
        return (0 until spans.size).map { Triple(spans.starts[it], spans.ends[it], spans.styles[it]) }
    }

    /**
     * The captures of a file used to take a `malloc` each, freed together at the end of the run: the allocator of
     * each thread that colored a large file kept hundreds of megabytes, which never went back to the system.
     */
    @Test
    fun `coloring a large file on several threads keeps no native memory`() {
        if (NativeMemory.heldMegabytes() == null) return // Only glibc tells what its allocator holds.
        val grammar = grammar()
        val lines = largeFile(functions = LARGE_FUNCTIONS)
        // The trees are freed too, but tree-sitter allocates them itself: they are not what is measured.
        val highlighters = List(THREADS) { TreeSitterHighlighter(grammar).apply { update(lines) } }
        highlighters.forEach { it.highlight(0, SCREEN_LINES) }
        val before = checkNotNull(NativeMemory.heldMegabytes())
        val workers = highlighters.map { highlighter -> thread { highlighter.highlight(0, lines.size) } }
        workers.forEach { it.join() }
        val kept = checkNotNull(NativeMemory.heldMegabytes()) - before
        highlighters.forEach { it.close() }
        println("PERF ${"native memory kept after coloring".padEnd(50)} $kept MB (budget $KEPT_BUDGET_MB MB)")
        assertTrue(kept <= KEPT_BUDGET_MB, "coloring ${lines.size} lines on $THREADS threads kept $kept MB")
    }

    private companion object {
        const val LARGE_FUNCTIONS = 5_000
        const val THREADS = 6
        const val SCREEN_LINES = 80
        const val MAX_COLORED = 10_000
        const val NANOS_PER_MILLI = 1_000_000
        const val BYTES_PER_MB = 1_048_576

        // Measured: 94 MB and 79 ms; 3,164 MB and 2,447 ms when the query ran over the whole lines.
        const val SCREEN_BUDGET_MB = 400L

        // Measured: 67 MB with the recycled block, 517 MB with a malloc for each capture.
        const val KEPT_BUDGET_MB = 200L
    }
}
