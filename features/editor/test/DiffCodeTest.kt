package org.example.kotlineditor.unit

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.ui.DiffCode
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffCodeTest {
    @Test
    fun `colors computed for a longer line never reach past the text of a shorter one`() {
        // The colors of the diff shown before, read for a frame against the lines of the next one.
        val stale = LineSpans(intArrayOf(0), intArrayOf(44), intArrayOf(1))
        val code = DiffCode(Language.fromFileName("Short.kt"), listOf(stale))

        val colored = code.colored("val a = 1", 0, DiffKind.Context)

        assertEquals("val a = 1", colored.text)
        val reach = colored.spanStyles.maxOfOrNull { it.end } ?: 0
        assertEquals(true, reach <= colored.text.length)
    }
}
