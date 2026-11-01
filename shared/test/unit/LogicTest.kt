package org.example.kotlineditor.unit

import org.example.kotlineditor.state.FileIndex
import org.example.kotlineditor.state.placeholder
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import org.example.kotlineditor.syntax.SyntaxColor
import org.example.kotlineditor.syntax.treesitter.LineSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxHighlighterTest {
    private fun colorAt(line: String, index: Int, language: Language = Language.Kotlin, inComment: Boolean = false) =
        SyntaxHighlighter.highlight(line, language, inComment).colorAt(index)

    private fun LineSpans.colorAt(index: Int): SyntaxColor? {
        val span = (0 until size).lastOrNull { index >= starts[it] && index < ends[it] } ?: return null
        return SyntaxColor.entries[styles[span]]
    }

    @Test
    fun `keywords, strings, numbers and comments are colored`() {
        val line = "val x = \"hi\" + 42 // note"
        assertEquals(SyntaxColor.Keyword, colorAt(line, 0))
        assertNull(colorAt(line, 4))
        assertEquals(SyntaxColor.StringLiteral, colorAt(line, 9))
        assertEquals(SyntaxColor.Number, colorAt(line, 15))
        assertEquals(SyntaxColor.Comment, colorAt(line, 20))
    }

    @Test
    fun `function declarations and annotations`() {
        assertEquals(SyntaxColor.Declaration, colorAt("fun main() {}", 5))
        assertEquals(SyntaxColor.Annotation, colorAt("@Composable fun x()", 3))
    }

    @Test
    fun `block comments spanning lines are tracked`() {
        val lines = listOf("a /* start", "inside", "end */ val", "val")
        val states = SyntaxHighlighter.blockCommentStates(lines, Language.Kotlin)!!
        assertEquals(listOf(false, true, true, false), states.toList())
        assertEquals(SyntaxColor.Comment, colorAt("inside", 0, inComment = true))
        assertEquals(SyntaxColor.Keyword, colorAt("end */ val", 8, inComment = true))
    }

    @Test
    fun `comment markers inside strings are ignored`() {
        val states = SyntaxHighlighter.blockCommentStates(listOf("val s = \"/*\"", "val"), Language.Kotlin)!!
        assertEquals(listOf(false, false), states.toList())
    }

    @Test
    fun `language detection by extension`() {
        assertEquals(Language.Kotlin, Language.fromFileName("build.gradle.kts"))
        assertEquals(Language.Java, Language.fromFileName("Main.java"))
        assertEquals(Language.Script, Language.fromFileName("config.yaml"))
        assertEquals(Language.Plain, Language.fromFileName("README"))
    }
}

class FuzzyMatchTest {
    @Test
    fun `matches characters in order`() {
        assertNotNull(FileIndex.fuzzyMatch("strkt", "Strings.kt"))
        assertNull(FileIndex.fuzzyMatch("tks", "Strings.kt"))
    }

    @Test
    fun `camel hump and prefix matches score higher`() {
        val hump = FileIndex.fuzzyMatch("ES", "EditorState.kt")!!.first
        val scattered = FileIndex.fuzzyMatch("ES", "ExpressionsUtils.kt")!!.first
        assertTrue(hump >= scattered)
        val exact = FileIndex.fuzzyMatch("App", "App.kt")!!.first
        val inner = FileIndex.fuzzyMatch("App", "MyApplication.kt")!!.first
        assertTrue(exact > inner)
    }
}

class FoldingModelTest {
    private val code =
        listOf(
            "/* header",
            "   comment */",
            "class A {",
            "    val s = \"{\"",
            "    fun f() { x() }",
            "    fun g() {",
            "        y()",
            "    }",
            "}",
        )

    @Test
    fun `regions cover multi-line blocks and comments, ignoring strings and one-liners`() {
        val regions =
            org.example.kotlineditor.state.FoldingModel
                .computeRegions(code, Language.Kotlin)
        assertEquals(mapOf(0 to 1, 2 to 8, 5 to 7), regions)
        assertTrue(
            org.example.kotlineditor.state.FoldingModel
                .computeRegions(code, Language.Plain)
                .isEmpty(),
        )
    }

    @Test
    fun `folding maps rows to lines`() {
        val doc =
            org.example.kotlineditor.state
                .Document(code, Language.Kotlin)
        val folding = doc.folding
        folding.regions =
            org.example.kotlineditor.state.FoldingModel
                .computeRegions(code, Language.Kotlin)
        assertEquals(9, folding.rowCount())
        folding.fold(5)
        assertEquals(7, folding.rowCount())
        assertEquals(8, folding.lineAt(6))
        assertEquals(5, folding.rowOf(7))
        assertTrue(folding.isHidden(6))
        assertEquals("...}", folding.placeholder(5, doc.lines))
        folding.fold(2)
        assertEquals(3, folding.rowCount())
        folding.unfoldAll()
        assertEquals(9, folding.rowCount())
    }
}

class StructureTest {
    @Test
    fun `kotlin outline lists types, functions and members with nesting`() {
        val lines =
            listOf(
                "package demo",
                "import x.Y",
                "/* class Hidden",
                "   fun hidden() */",
                "data class User(val name: String) {",
                "    private val cache = 1",
                "    suspend fun load(): User {",
                "        val local = 2",
                "    }",
                "}",
                "interface Repo",
                "object Registry",
                "fun <T> List<T>.second(): T = this[1]",
            )
        val states = SyntaxHighlighter.blockCommentStates(lines, Language.Kotlin)
        val outline =
            org.example.kotlineditor.syntax.Structure
                .outline(lines, Language.Kotlin, states)
        assertEquals(
            listOf("User" to 0, "cache" to 1, "load" to 1, "Repo" to 0, "Registry" to 0, "second" to 0),
            outline.map { it.name to it.depth },
        )
        assertEquals(org.example.kotlineditor.syntax.DeclarationKind.Interface, outline[3].kind)
    }

    @Test
    fun `java outline lists types and methods but not control flow`() {
        val lines =
            listOf(
                "public class Main {",
                "    public static void main(String[] args) {",
                "        if (args.length > 0) {",
                "        }",
                "    }",
                "    private int count() {",
                "}",
            )
        assertEquals(
            listOf("Main", "main", "count"),
            org.example.kotlineditor.syntax.Structure
                .outline(lines, Language.Java)
                .map { it.name },
        )
    }
}
