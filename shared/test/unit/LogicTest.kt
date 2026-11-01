package org.example.kotlineditor.unit

import org.example.kotlineditor.state.FileIndex
import org.example.kotlineditor.state.placeholder
import org.example.kotlineditor.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
