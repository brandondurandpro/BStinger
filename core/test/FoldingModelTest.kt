package org.example.kotlineditor.unit

import org.example.kotlineditor.state.insert
import org.example.kotlineditor.state.placeholder
import org.example.kotlineditor.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `the server's regions are added to the editor's, each region shown once, and go when lines are inserted`() {
        val document =
            org.example.kotlineditor.state
                .Document(code, Language.Kotlin)
        val folding = document.folding
        folding.regions =
            org.example.kotlineditor.state.FoldingModel
                .computeRegions(code, Language.Kotlin)
        // 5 to 7 starts like the editor's, 4 to 8 ends like it: only the run of lines 3 to 4 is new.
        folding.serverRegions(mapOf(3 to 4, 5 to 7, 4 to 8), code.size)
        assertEquals(mapOf(0 to 1, 2 to 8, 5 to 7, 3 to 4), folding.regions)
        document.moveTo(
            org.example.kotlineditor.state
                .TextPos(0, 0),
        )
        document.insert("\n")
        assertEquals(mapOf(0 to 1, 2 to 8, 5 to 7), folding.regions)
        // An answer for a text of another length is not used.
        folding.serverRegions(mapOf(3 to 4), code.size)
        assertEquals(mapOf(0 to 1, 2 to 8, 5 to 7), folding.regions)
    }
}
