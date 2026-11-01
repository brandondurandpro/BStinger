package org.example.kotlineditor.syntax

import io.github.treesitter.jtreesitter.InputEncoding
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Point
import io.github.treesitter.jtreesitter.Query
import io.github.treesitter.jtreesitter.QueryCursor
import io.github.treesitter.jtreesitter.QueryMatch
import io.github.treesitter.jtreesitter.Tree
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.LineSpanBuilder
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.LoadedGrammar
import org.example.kotlineditor.syntax.treesitter.NativePlatform
import org.example.kotlineditor.syntax.treesitter.QueryPredicates
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import org.example.kotlineditor.syntax.SyntaxColor
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `the runtime is bundled for every platform of the grammars`() {
        val platforms = GrammarSpec.all.flatMap { it.platforms }.toSet()
        val extensions = mapOf("linux-gnu" to "so", "macos" to "dylib", "windows" to "dll")
        for (platform in platforms) {
            val extension = extensions.entries.single { platform.endsWith(it.key) }.value
            assertNotNull(javaClass.getResource("/tree-sitter/runtime/$platform-tree-sitter.$extension"), platform)
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
    private fun color(name: String) = SyntaxColor.entries.getOrNull(CaptureStyles.styleOf(name))

    @Test
    fun `a capture takes the style of its longest known prefix`() {
        assertEquals(SyntaxColor.Keyword, color("keyword"))
        assertEquals(SyntaxColor.Keyword, color("keyword.control.conditional"))
        assertEquals(SyntaxColor.StringLiteral, color("string"))
        assertEquals(SyntaxColor.StringEscape, color("string.escape"))
        assertEquals(SyntaxColor.Declaration, color("function.method.call"))
        assertEquals(SyntaxColor.Keyword, color("type.builtin"))
        assertEquals(SyntaxColor.Number, color("constant.numeric.integer"))
        assertEquals(SyntaxColor.Property, color("variable.other.member"))
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
        assertNotNull(runBlocking { store.await(GrammarSpec.all.single { it.id == id }) }, store.lastFailure)

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

    @Test
    fun `the tree is freed on release and parsed again by the next update`() {
        val lines = listOf("fun main() = println(1)")
        TreeSitterHighlighter(grammar("kotlin")).use { highlighter ->
            highlighter.update(lines)
            val colored = highlighter.highlight(0, 1)
            assertTrue(highlighter.isParsed)
            highlighter.release()
            assertFalse(highlighter.isParsed)
            assertEquals(emptyMap(), highlighter.highlight(0, 1))
            highlighter.update(lines)
            assertTrue(highlighter.isParsed)
            assertEquals(colored, highlighter.highlight(0, 1))
        }
    }

    @Test
    fun `text predicates hold as jtreesitter checks them`() {
        val kotlin = grammar("kotlin")
        val source =
            """
            ((simple_identifier) @a (#match? @a "^[A-Z]"))
            ((simple_identifier) @b (#not-match? @b "x$"))
            ((simple_identifier) @c (#any-of? @c "it" "field" "a\"b"))
            ((simple_identifier) @d (#not-any-of? @d "it"))
            ((simple_identifier) @e (#eq? @e "it")) ; a comment (#eq? @e "no")
            ((simple_identifier) @f (#not-eq? @f "it"))
            (call_expression (simple_identifier) @g (call_suffix (value_arguments (value_argument (simple_identifier) @h))) (#eq? @g @h))
            (call_expression (simple_identifier) @i (call_suffix (value_arguments (value_argument (simple_identifier) @j))) (#not-eq? @i @j))
            ((string_literal) @k (#match? @k "^\"multi\nline"))
            ((simple_identifier) @l)
            """.trimIndent()
        val lines = listOf("val Big = it.field", "f(f) + g(x) + ax", "val s = \"multi", "line\" // \u00e9t\u00e9 it")
        Query(kotlin.language, source).use { query ->
            val predicates = QueryPredicates.read(query, source)
            val text = lines.joinToString("\n")
            // Parsed from a string, the tree keeps its text and jtreesitter checks the predicates. Not from a callback.
            val withText = Parser(kotlin.language).use { it.parse(text, InputEncoding.UTF_16LE).get() }
            val expected = withText.use { captures(query, it) }
            val read = { offset: Int, _: Point -> text.substring(offset / 2).ifEmpty { null } }
            val tree = Parser(kotlin.language).use { it.parse(read, InputEncoding.UTF_16LE, null, null).get() }
            val actual =
                captures(query, tree) { match ->
                    predicates.hold(match) { start, end -> text.substring(start, end) }
                }
            val unchecked = captures(query, tree)
            tree.close()
            assertEquals(expected, actual)
            assertTrue(unchecked.size > actual.size, "the predicates rule some captures out")
            assertTrue(expected.any { it.startsWith("c ") } && expected.any { it.startsWith("k ") }, "$expected")
        }
    }

    // The captures of [query] in [tree], as "name text-range", those of the matches that [hold].
    private fun captures(query: Query, tree: Tree, hold: (QueryMatch) -> Boolean = { true }): List<String> =
        QueryCursor(query).use { cursor ->
            cursor
                .findCaptures(tree.rootNode)
                .toList()
                .filter { hold(it.value) }
                .map {
                    val node = it.value.captures()[it.key].node()
                    "${it.value.captures()[it.key].name()} ${node.startByte / 2}-${node.endByte / 2}"
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
        assertNotNull(runBlocking { store.await(kotlin) }, store.lastFailure)
        assertNotNull(runBlocking { store.await(kotlin) }, store.lastFailure)
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
