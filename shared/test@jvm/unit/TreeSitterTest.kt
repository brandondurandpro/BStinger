package org.example.kotlineditor.unit

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.LineSpanBuilder
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.LoadedGrammar
import org.example.kotlineditor.syntax.treesitter.NativePlatform
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import org.example.kotlineditor.ui.IdeColors
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrammarCatalogTest {
    @Test
    fun `files find their grammar by name then by extension`() {
        assertEquals("kotlin", GrammarSpec.forFileName("Main.kt")?.id)
        assertEquals("kotlin", GrammarSpec.forFileName("build.gradle.kts")?.id)
        assertEquals("json", GrammarSpec.forFileName("package.JSON")?.id)
        assertEquals("dockerfile", GrammarSpec.forFileName("Dockerfile")?.id)
        assertEquals("cmake", GrammarSpec.forFileName("CMakeLists.txt")?.id)
        assertEquals("tsx", GrammarSpec.forFileName("App.tsx")?.id)
        assertNull(GrammarSpec.forFileName("notes.txt"))
        assertNull(GrammarSpec.forFileName("LICENSE"))
    }

    @Test
    fun `every grammar has a highlight query and an extension or a file name of its own`() {
        val ids = GrammarSpec.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (spec in GrammarSpec.all) {
            assertNotNull(javaClass.getResource("/tree-sitter/${spec.id}/highlights.scm"), spec.id)
            assertTrue(spec.extensions.isNotEmpty() || spec.fileNames.isNotEmpty(), spec.id)
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")), spec.id)
        }
        val extensions = GrammarSpec.all.flatMap { it.extensions }
        assertEquals(extensions.size, extensions.toSet().size, "an extension belongs to two grammars")
    }

    @Test
    fun `the test fixtures are the jars of the catalog`() {
        // libs.versions.toml must keep the versions of the catalog for the grammars the tests download.
        for (id in listOf("kotlin", "json")) {
            val spec = GrammarSpec.all.single { it.id == id }
            val jar = assertNotNull(LocalGrammarRepository.jarOf(spec), "$spec is not on the test classpath")
            val sha =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(jar)
                    .joinToString("") { "%02x".format(it) }
            assertEquals(spec.sha256, sha, "$spec on the test classpath is not the one of the catalog")
        }
    }

    @Test
    fun `the platform is named like the prebuilt libraries`() {
        assertEquals("x86_64-linux-gnu", NativePlatform.forSystem("Linux", "amd64")?.id)
        assertEquals("aarch64-linux-gnu", NativePlatform.forSystem("Linux", "aarch64")?.id)
        assertEquals("aarch64-macos", NativePlatform.forSystem("Mac OS X", "aarch64")?.id)
        assertEquals("x86_64-macos", NativePlatform.forSystem("Mac OS X", "x86_64")?.id)
        assertEquals("x86_64-windows", NativePlatform.forSystem("Windows 11", "amd64")?.id)
        assertEquals("dll", NativePlatform.forSystem("Windows 11", "amd64")?.libraryExtension)
        assertNull(NativePlatform.forSystem("Windows 11", "aarch64"))
        assertNull(NativePlatform.forSystem("FreeBSD", "amd64"))
        assertNull(NativePlatform.forSystem("Linux", "riscv64"))
    }
}

class CaptureStylesTest {
    private fun color(name: String) = CaptureStyles.palette.getOrNull(CaptureStyles.styleOf(name))?.color

    @Test
    fun `a capture takes the style of its longest known prefix`() {
        assertEquals(IdeColors.Keyword, color("keyword"))
        assertEquals(IdeColors.Keyword, color("keyword.control.conditional"))
        assertEquals(IdeColors.StringLiteral, color("string"))
        assertEquals(IdeColors.StringEscape, color("string.escape"))
        assertEquals(IdeColors.FunctionDeclaration, color("function.method.call"))
        assertEquals(IdeColors.Keyword, color("type.builtin"))
        assertEquals(IdeColors.Number, color("constant.numeric.integer"))
        assertEquals(IdeColors.Property, color("variable.other.member"))
    }

    @Test
    fun `some captures keep the text color and others are no highlight at all`() {
        assertEquals(CaptureStyles.NONE, CaptureStyles.styleOf("variable"))
        assertEquals(CaptureStyles.NONE, CaptureStyles.styleOf("punctuation.bracket"))
        assertEquals(CaptureStyles.NONE, CaptureStyles.styleOf("constructor"))
        assertEquals(CaptureStyles.IGNORED, CaptureStyles.styleOf("_name"))
        assertEquals(CaptureStyles.IGNORED, CaptureStyles.styleOf("spell"))
        assertEquals(CaptureStyles.IGNORED, CaptureStyles.styleOf("local.definition"))
    }
}

class LineSpanBuilderTest {
    private fun LineSpans.list() = (0 until size).map { Triple(starts[it], ends[it], styles[it]) }

    @Test
    fun `an inner node paints over the outer one`() {
        val builder = LineSpanBuilder(10)
        builder.add(0, 10, pattern = 5, style = 1)
        builder.add(3, 5, pattern = 0, style = 2)
        assertEquals(listOf(Triple(0, 3, 1), Triple(3, 5, 2), Triple(5, 10, 1)), builder.build().list())
    }

    @Test
    fun `among captures of the same node the last pattern wins, even without color`() {
        val builder = LineSpanBuilder(4)
        builder.add(0, 4, pattern = 7, style = CaptureStyles.NONE)
        builder.add(0, 4, pattern = 2, style = 3)
        assertEquals(emptyList(), builder.build().list())
        val other = LineSpanBuilder(4)
        other.add(0, 4, pattern = 2, style = CaptureStyles.NONE)
        other.add(0, 4, pattern = 7, style = 3)
        assertEquals(listOf(Triple(0, 4, 3)), other.build().list())
    }

    @Test
    fun `ranges are cut to the line and empty ones dropped`() {
        val builder = LineSpanBuilder(3)
        builder.add(-2, 1, pattern = 0, style = 1)
        builder.add(2, 9, pattern = 0, style = 2)
        builder.add(1, 1, pattern = 0, style = 3)
        assertEquals(listOf(Triple(0, 1, 1), Triple(2, 3, 2)), builder.build().list())
    }

    @Test
    fun `spans clipped to a shorter line keep what fits`() {
        val spans = LineSpans(intArrayOf(0, 4, 8), intArrayOf(3, 7, 9), intArrayOf(1, 2, 3))
        assertEquals(listOf(Triple(0, 3, 1), Triple(4, 5, 2)), spans.clippedTo(5).list())
        assertEquals(spans, spans.clippedTo(20))
    }
}

class TreeSitterHighlighterTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    @AfterTest
    fun close() = repository.close()

    private fun grammar(id: String): LoadedGrammar =
        assertNotNull(runBlocking { store.await(GrammarSpec.all.single { it.id == id }) })

    private fun styled(spans: LineSpans?, line: String): Map<String, Int> =
        (0 until (spans?.size ?: 0)).associate {
            line.substring(spans!!.starts[it], spans.ends[it]) to spans.styles[it]
        }

    private fun style(name: String) = CaptureStyles.styleOf(name)

    @Test
    fun `Kotlin keywords, strings, calls and comments are colored by the grammar`() {
        val lines = listOf("fun main() {", "    val greeting = \"Hello\" // hi", "    println(greeting)", "}")
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            val spans = highlighter.highlight(0, lines.size)
            assertEquals(style("keyword"), styled(spans[0], lines[0])["fun"])
            assertEquals(style("function"), styled(spans[0], lines[0])["main"])
            assertEquals(style("keyword"), styled(spans[1], lines[1])["val"])
            assertEquals(style("string"), styled(spans[1], lines[1])["\"Hello\""])
            assertEquals(style("comment"), styled(spans[1], lines[1])["// hi"])
            assertEquals(style("function"), styled(spans[2], lines[2])["println"])
            assertEquals(null, styled(spans[2], lines[2])["greeting"], "a variable keeps the text color")
        }
    }

    @Test
    fun `an expression body call is colored`() {
        val lines = listOf("fun f() = g(1)")
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            val spans = highlighter.highlight(0, 1)
            assertEquals(style("function"), styled(spans[0], lines[0])["g"])
        }
    }

    @Test
    fun `a block comment colors every line it spans`() {
        val lines = listOf("/* one", "two", "three */ val x = 1")
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            val spans = highlighter.highlight(0, lines.size)
            assertEquals(mapOf("/* one" to style("comment")), styled(spans[0], lines[0]))
            assertEquals(mapOf("two" to style("comment")), styled(spans[1], lines[1]))
            assertEquals(style("comment"), styled(spans[2], lines[2])["three */"])
            assertEquals(style("number"), styled(spans[2], lines[2])["1"])
        }
    }

    @Test
    fun `only the lines asked for are colored`() {
        val lines = List(50) { "val v$it = $it" }
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            assertEquals((10 until 20).toSet(), highlighter.highlight(10, 20).keys)
            assertEquals(emptySet(), highlighter.highlight(60, 70).keys)
        }
    }

    @Test
    fun `incremental parses color like a fresh parse after any edit`() {
        val random = Random(42)
        val grammar = grammar("kotlin")
        var lines = listOf("package demo", "", "class A {", "    fun f(x: Int) = x * 2 // twice", "}")
        val words = listOf("val", "\"text\"", "/*", "*/", "fun g()", "{", "}", "42", "// c", "x.y()", "\"\"\"", "")
        TreeSitterHighlighter(grammar).use { incremental ->
            incremental.update(lines)
            repeat(EDITS) { step ->
                lines = edit(lines, random, words)
                incremental.update(lines)
                val expected =
                    TreeSitterHighlighter(grammar).use { fresh ->
                        fresh.update(lines)
                        fresh.highlight(0, lines.size)
                    }
                assertEquals(expected, incremental.highlight(0, lines.size), "after edit $step: $lines")
            }
        }
    }

    // A random edit: lines inserted, removed, replaced or changed, at the start, the middle or the end.
    private fun edit(lines: List<String>, random: Random, words: List<String>): List<String> {
        val result = lines.toMutableList()
        val at = random.nextInt(result.size)
        when (random.nextInt(5)) {
            0 -> result.add(random.nextInt(result.size + 1), words.random(random))
            1 -> if (result.size > 1) result.removeAt(at)
            2 -> result[at] = result[at] + " " + words.random(random)
            3 -> result[at] = result[at].take(random.nextInt(result[at].length + 1))
            else -> if (result.size > 1) result.subList(at, minOf(result.size, at + 2)).clear()
        }
        return result.ifEmpty { listOf("") }
    }

    @Test
    fun `tabs and characters beyond the basic plane keep their offsets`() {
        val lines = listOf("""${'\t'}val s = "😀" // été""")
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            val spans = styled(highlighter.highlight(0, 1)[0], lines[0])
            assertEquals(style("keyword"), spans["val"])
            assertEquals(style("string"), spans[""""😀""""])
            assertEquals(style("comment"), spans["// été"])
        }
    }

    private companion object {
        const val EDITS = 200
    }
}

class GrammarStoreTest {
    private val repository = LocalGrammarRepository()
    private val directory = Files.createTempDirectory("grammar-store").toFile()
    private val kotlin = GrammarSpec.all.single { it.id == "kotlin" }

    @AfterTest
    fun close() {
        repository.close()
        directory.deleteRecursively()
    }

    @Test
    fun `a grammar is downloaded once, then loaded from the cache`() {
        val store = TestGrammars.store(repository, directory)
        assertNotNull(runBlocking { store.await(kotlin) })
        assertNotNull(runBlocking { store.await(kotlin) })
        assertEquals(1, store.downloads)
        val restarted = TestGrammars.store(repository, directory)
        assertNotNull(runBlocking { restarted.await(kotlin) })
        assertEquals(0, restarted.downloads)
        assertEquals(1, repository.jarsServed)
    }

    @Test
    fun `a download that is not the expected jar is retried until it is`() {
        repository.captivePortal = true
        val store = TestGrammars.store(repository, directory)
        val waiting = Thread { runBlocking { store.await(kotlin) } }.apply { start() }
        Thread.sleep(TestGrammars.RETRY_DELAY_MS * 3)
        assertTrue(waiting.isAlive, "a page instead of the jar must not be loaded")
        repository.captivePortal = false
        waiting.join(TIMEOUT_MS)
        assertTrue(!waiting.isAlive)
        assertEquals(1, repository.jarsServed)
    }

    @Test
    fun `nothing is downloaded on a platform without prebuilt library`() {
        val store = TestGrammars.store(repository, directory, platform = NativePlatform("riscv64-linux-gnu", "so"))
        assertNull(runBlocking { store.await(kotlin) })
        assertEquals(0, repository.jarsServed)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
