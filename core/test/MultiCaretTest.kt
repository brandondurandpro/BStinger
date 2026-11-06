package org.example.kotlineditor.unit

import org.example.kotlineditor.state.Caret
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.backspace
import org.example.kotlineditor.state.caretCount
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.forEachCaret
import org.example.kotlineditor.state.insert
import org.example.kotlineditor.state.moveLeft
import org.example.kotlineditor.state.newLine
import org.example.kotlineditor.state.paste
import org.example.kotlineditor.state.removeExtraCarets
import org.example.kotlineditor.state.selectAllOccurrences
import org.example.kotlineditor.state.selectColumns
import org.example.kotlineditor.state.selectNextOccurrence
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.state.selectedTextOfCarets
import org.example.kotlineditor.state.toggleCaretAt
import org.example.kotlineditor.state.typeChar
import org.example.kotlineditor.state.unselectOccurrence
import org.example.kotlineditor.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiCaretTest {
    private fun doc(text: String) = Document(text.split('\n'), Language.Kotlin)

    private fun Document.type(text: String) = text.forEach { c -> forEachCaret { typeChar(c) } }

    @Test
    fun `typing at several carets on the same line shifts the later ones`() {
        val d = doc("a b c")
        d.moveTo(TextPos(0, 1))
        d.toggleCaretAt(TextPos(0, 3))
        d.toggleCaretAt(TextPos(0, 5))
        d.type("xy")
        assertEquals("axy bxy cxy", d.lines[0])
        assertEquals(3, d.caretCount)
        assertEquals(setOf(3, 7, 11), d.carets().map { it.pos.column }.toSet())
    }

    @Test
    fun `typing at several carets is undone and redone at once, restoring every caret`() {
        val d = doc("one\ntwo\nthree")
        d.moveTo(TextPos(0, 0))
        d.toggleCaretAt(TextPos(1, 0))
        d.toggleCaretAt(TextPos(2, 0))
        val before = d.carets()
        d.type("ab")
        assertEquals(listOf("abone", "abtwo", "abthree"), d.lines.toList())
        d.undo()
        assertEquals(listOf("one", "two", "three"), d.lines.toList())
        assertEquals(before.map { it.pos }.toSet(), d.carets().map { it.pos }.toSet())
        d.redo()
        assertEquals(listOf("abone", "abtwo", "abthree"), d.lines.toList())
        assertEquals(3, d.caretCount)
    }

    @Test
    fun `Alt+click on an existing caret removes it, a plain move removes the others`() {
        val d = doc("hello\nworld")
        d.toggleCaretAt(TextPos(1, 2))
        assertEquals(2, d.caretCount)
        d.toggleCaretAt(TextPos(1, 2))
        assertEquals(1, d.caretCount)
        // The last caret stays.
        d.toggleCaretAt(TextPos(0, 0))
        assertEquals(1, d.caretCount)
        d.toggleCaretAt(TextPos(1, 1))
        d.moveTo(TextPos(0, 3))
        assertEquals(1, d.caretCount)
    }

    @Test
    fun `carets meeting after a move become one`() {
        val d = doc("ab")
        d.moveTo(TextPos(0, 0))
        d.toggleCaretAt(TextPos(0, 1))
        d.forEachCaret { moveLeft() }
        assertEquals(1, d.caretCount)
        assertEquals(TextPos(0, 0), d.caret)
    }

    @Test
    fun `backspace and Enter at every caret`() {
        val d = doc("  ab\n  cd")
        d.moveTo(TextPos(0, 4))
        d.toggleCaretAt(TextPos(1, 4))
        d.forEachCaret { backspace() }
        assertEquals(listOf("  a", "  c"), d.lines.toList())
        d.forEachCaret { newLine() }
        assertEquals(listOf("  a", "  ", "  c", "  "), d.lines.toList())
        assertEquals(setOf(TextPos(1, 2), TextPos(3, 2)), d.carets().map { it.pos }.toSet())
    }

    @Test
    fun `Alt+J selects the word, then its next whole-word occurrences, wrapping around`() {
        val d = doc("val foo = 1\nval foobar = foo\nfoo()")
        d.moveTo(TextPos(1, 14))
        assertTrue(d.selectNextOccurrence())
        assertEquals("foo", d.selectedText())
        assertTrue(d.selectNextOccurrence())
        assertEquals(TextPos(2, 0), d.carets().first().start)
        assertTrue(d.selectNextOccurrence())
        assertEquals(TextPos(0, 4), d.carets().first().start)
        assertEquals(3, d.caretCount)
        // Every occurrence is taken; foobar is not a whole-word one.
        assertFalse(d.selectNextOccurrence())
        d.type("bar")
        assertEquals(listOf("val bar = 1", "val foobar = bar", "bar()"), d.lines.toList())
    }

    @Test
    fun `Shift+Alt+J removes the last occurrence added`() {
        val d = doc("x x x")
        d.moveTo(TextPos(0, 0))
        d.selectNextOccurrence()
        d.selectNextOccurrence()
        d.selectNextOccurrence()
        assertEquals(3, d.caretCount)
        d.unselectOccurrence()
        assertEquals(2, d.caretCount)
        assertEquals(TextPos(0, 2), d.carets().first().start)
    }

    @Test
    fun `a selected text that is not a word matches inside words too`() {
        val d = doc("ab abc\nab")
        d.select(TextPos(0, 0), TextPos(0, 2))
        d.selectNextOccurrence()
        d.selectNextOccurrence()
        assertEquals(3, d.caretCount)
    }

    @Test
    fun `selecting all occurrences puts a caret on each`() {
        val d = doc("a.b\nb.a\na")
        d.moveTo(TextPos(1, 2))
        assertTrue(d.selectAllOccurrences())
        assertEquals(3, d.caretCount)
        assertEquals(TextPos(1, 2), d.carets().first().start)
        d.type("z")
        assertEquals(listOf("z.b", "b.z", "z"), d.lines.toList())
    }

    @Test
    fun `column selection puts a caret per line, short lines get one at their end`() {
        val d = doc("abcdef\nab\nabcdef")
        d.selectColumns(0, 1, 2, 4)
        assertEquals(3, d.caretCount)
        assertEquals(TextPos(2, 4), d.caret)
        assertEquals("bcd\nb\nbcd", d.selectedTextOfCarets())
        d.forEachCaret { insert("") }
        assertEquals(listOf("aef", "a", "aef"), d.lines.toList())
        d.removeExtraCarets()
        assertEquals(1, d.caretCount)
    }

    @Test
    fun `pasting as many lines as carets gives each caret its line`() {
        val d = doc("a\nb\nc")
        d.selectColumns(0, 1, 2, 1)
        d.paste("1\n2\n3")
        assertEquals(listOf("a1", "b2", "c3"), d.lines.toList())
        d.paste("x")
        assertEquals(listOf("a1x", "b2x", "c3x"), d.lines.toList())
    }

    @Test
    fun `other carets follow an edit made at the primary caret alone`() {
        val d = doc("ab\ncd")
        d.moveTo(TextPos(1, 1))
        d.toggleCaretAt(TextPos(0, 1))
        d.replace(TextPos(0, 0), TextPos(0, 0), "x\n")
        assertEquals(Caret(TextPos(2, 1)), d.extraCarets.single())
    }

    @Test
    fun `Enter at two carets of the same line splits it twice`() {
        val d = doc("ab cd")
        d.moveTo(TextPos(0, 2))
        d.toggleCaretAt(TextPos(0, 5))
        d.forEachCaret { newLine() }
        assertEquals(listOf("ab", " cd", " "), d.lines.toList())
        assertEquals(setOf(TextPos(1, 0), TextPos(2, 1)), d.carets().map { it.pos }.toSet())
    }

    @Test
    fun `Delete at adjacent carets deletes both characters and merges the carets`() {
        val d = doc("abc")
        d.moveTo(TextPos(0, 0))
        d.toggleCaretAt(TextPos(0, 1))
        d.forEachCaret { delete() }
        assertEquals(listOf("c"), d.lines.toList())
        assertEquals(1, d.caretCount)
    }
}
