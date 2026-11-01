package org.example.kotlineditor.syntax

import org.example.kotlineditor.syntax.treesitter.LineSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
