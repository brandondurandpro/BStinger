package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.Tags
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Memory taken by tree-sitter highlighting: opens a 10,002-line Kotlin file, a 10,000-line JSON file and 10 small
 * Kotlin files through the UI, types in the large one, and prints the heap used after a GC and the resident memory of
 * the process (which counts the native syntax trees). Run it once with grammars and once without, in two JVMs, and
 * compare. Skipped unless run with `-Deditor.benchmark=true`; `-Deditor.grammars=false` runs without grammars, e.g.
 * `JAVA_TOOL_OPTIONS="-Deditor.benchmark=true -Deditor.grammars=false" ./kotlin test -m shared
 * --include-classes='*SyntaxHighlightingMemoryBenchmark'`. Linux only (resident memory from `/proc`).
 */
@OptIn(ExperimentalTestApi::class)
class SyntaxHighlightingMemoryBenchmark {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `memory of highlighted files`() {
        if (System.getProperty("editor.benchmark") != "true") return
        val grammars = System.getProperty("editor.grammars") != "false"
        runComposeUiTest {
            project.file(
                "src/Big.kt",
                (0 until CLASSES).joinToString("\n") { i ->
                    "class C$i(val name: String) {\n    fun f$i(x: Int): Int = x * $i + name.length // $i\n}"
                },
            )
            project.file(
                "data/big.json",
                (0 until JSON_LINES).joinToString(",\n", "[\n", "\n]") { """  {"id": $it, "ok": true}""" },
            )
            repeat(SMALL_FILES) { project.file("src/small/S$it.kt", SampleProject.APP_KT) }
            val store = if (grammars) TestGrammars.shared else null
            // Grammars loaded beforehand: their libraries are counted in the baseline, as once used in a session.
            store?.let { s ->
                GrammarSpec.all.filter { it.id in setOf("kotlin", "json") }.forEach { runBlocking { s.await(it) } }
            }
            launchWithProject(project, grammars = store)
            val baseline = sample("project open, no file")
            openFromTree("src/Big.kt")
            awaitColor(0, "class", IdeColors.Keyword)
            if (grammars) awaitColor(1, "length", IdeColors.Property)
            val big = sample("+ 10,002-line Kotlin file")
            openFromTree("data/big.json")
            if (grammars) awaitColor(1, "true", IdeColors.Keyword)
            val json = sample("+ 10,000-line JSON file")
            repeat(SMALL_FILES) { openFromTree("src/small/S$it.kt") }
            if (grammars) awaitColor(8, "println", IdeColors.FunctionDeclaration)
            val small = sample("+ 10 small Kotlin files")
            openFromTree("src/Big.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            pressInEditor(Key.MoveEnd, ctrl = true)
            repeat(TYPED_LINES) { typeInEditor("\nval z = name.length") }
            val typed = sample("+ 20 lines typed in the large file")
            println("MEMORY grammars=$grammars")
            val (baseHeap, baseRss) = baseline.second
            for ((label, value) in listOf(baseline, big, json, small, typed)) {
                val (heap, rss) = value
                println(
                    "MEMORY ${label.padEnd(36)} heap ${heap / MB} MB (+${(heap - baseHeap) / MB}), " +
                        "RSS ${rss / MB} MB (+${(rss - baseRss) / MB})",
                )
            }
        }
    }

    // Heap used after a full GC, and resident memory of the process.
    private fun ComposeUiTest.sample(label: String): Pair<String, Pair<Long, Long>> {
        waitForIdle()
        repeat(GC_ROUNDS) {
            System.gc()
            Thread.sleep(GC_PAUSE_MS)
        }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val rssLine = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
        val rss = rssLine.split(Regex("\\s+"))[1].toLong() * KB
        return label to (heap to rss)
    }

    private companion object {
        const val CLASSES = 3_334
        const val JSON_LINES = 10_000
        const val SMALL_FILES = 10
        const val TYPED_LINES = 20
        const val GC_ROUNDS = 3
        const val GC_PAUSE_MS = 300L
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}
