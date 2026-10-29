package org.example.kotlineditor.unit

import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.backspace
import org.example.kotlineditor.state.columnForVisual
import org.example.kotlineditor.state.deleteLines
import org.example.kotlineditor.state.duplicate
import org.example.kotlineditor.state.extendSelection
import org.example.kotlineditor.state.findAll
import org.example.kotlineditor.state.identifierAt
import org.example.kotlineditor.state.indent
import org.example.kotlineditor.state.insert
import org.example.kotlineditor.state.matchingBracket
import org.example.kotlineditor.state.moveHome
import org.example.kotlineditor.state.moveLines
import org.example.kotlineditor.state.moveRight
import org.example.kotlineditor.state.moveToDocumentEnd
import org.example.kotlineditor.state.moveVertically
import org.example.kotlineditor.state.newLine
import org.example.kotlineditor.state.reload
import org.example.kotlineditor.state.replaceAll
import org.example.kotlineditor.state.selectAll
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.state.shrinkSelection
import org.example.kotlineditor.state.toggleLineComment
import org.example.kotlineditor.state.typeChar
import org.example.kotlineditor.state.visualColumn
import org.example.kotlineditor.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentTest {
    private fun doc(text: String, language: Language = Language.Kotlin) = Document(text.split('\n'), language)

    @Test
    fun `insert multi-line text and undo it`() {
        val d = doc("ab")
        d.moveTo(TextPos(0, 1))
        d.insert("X\nY\nZ")
        assertEquals("aX\nY\nZb", d.text())
        assertEquals(TextPos(2, 1), d.caret)
        assertTrue(d.isModified)
        d.undo()
        assertEquals("ab", d.text())
        assertEquals(TextPos(0, 1), d.caret)
        d.redo()
        assertEquals("aX\nY\nZb", d.text())
    }

    @Test
    fun `consecutive typing is undone as one word`() {
        val d = doc("")
        "hello".forEach { d.insert(it.toString()) }
        d.insert(" ")
        "world".forEach { d.insert(it.toString()) }
        d.undo()
        assertEquals("hello ", d.text())
        d.undo()
        assertEquals("hello", d.text())
        d.undo()
        assertEquals("", d.text())
    }

    @Test
    fun `backspace joins lines and deletes the selection`() {
        val d = doc("ab\ncd")
        d.moveTo(TextPos(1, 0))
        d.backspace()
        assertEquals("abcd", d.text())
        d.select(TextPos(0, 1), TextPos(0, 3))
        d.backspace()
        assertEquals("ad", d.text())
        assertEquals(TextPos(0, 1), d.caret)
    }

    @Test
    fun `word navigation and deletion`() {
        val d = doc("val someName = 42")
        d.moveTo(TextPos(0, 0))
        d.moveRight(word = true)
        assertEquals(3, d.caret.column)
        d.moveRight(word = true)
        assertEquals(12, d.caret.column)
        d.backspace(word = true)
        assertEquals("val  = 42", d.text())
    }

    @Test
    fun `smart home toggles between indentation and column zero`() {
        val d = doc("    code")
        d.moveTo(TextPos(0, 6))
        d.moveHome()
        assertEquals(4, d.caret.column)
        d.moveHome()
        assertEquals(0, d.caret.column)
    }

    @Test
    fun `vertical movement keeps the preferred column`() {
        val d = doc("long line here\nab\nanother long line")
        d.moveTo(TextPos(0, 10))
        d.moveVertically(1)
        assertEquals(TextPos(1, 2), d.caret)
        d.moveVertically(1)
        assertEquals(TextPos(2, 10), d.caret)
    }

    @Test
    fun `newline auto-indents`() {
        val d = doc("    if (x) {")
        d.moveToDocumentEnd()
        d.newLine()
        assertEquals("    if (x) {\n        ", d.text())
    }

    @Test
    fun `indent and outdent selected lines`() {
        val d = doc("a\nb")
        d.selectAll()
        d.indent()
        assertEquals("    a\n    b", d.text())
        d.indent(outdent = true)
        assertEquals("a\nb", d.text())
    }

    @Test
    fun `toggle comment on a selection`() {
        val d = doc("a()\n\nb()")
        d.selectAll()
        d.toggleLineComment()
        assertEquals("// a()\n\n// b()", d.text())
        d.toggleLineComment()
        assertEquals("a()\n\nb()", d.text())
    }

    @Test
    fun `duplicate and delete lines`() {
        val d = doc("one\ntwo")
        d.moveTo(TextPos(0, 1))
        d.duplicate()
        assertEquals("one\none\ntwo", d.text())
        assertEquals(TextPos(1, 1), d.caret)
        d.deleteLines()
        d.deleteLines()
        assertEquals("one", d.text())
    }

    @Test
    fun `find all occurrences`() {
        val d = doc("Foo foo\nbar FOO")
        assertEquals(listOf(TextPos(0, 0), TextPos(0, 4), TextPos(1, 4)), d.findAll("foo"))
        assertEquals(listOf(TextPos(0, 4)), d.findAll("foo", ignoreCase = false))
    }

    @Test
    fun `saving resets the modified flag`() {
        val d = doc("x")
        d.insert("y")
        d.markSaved()
        assertFalse(d.isModified)
        d.undo()
        assertTrue(d.isModified)
    }

    @Test
    fun `line separator is preserved`() {
        val d = Document(listOf("a", "b"), Language.Plain, "\r\n")
        assertEquals("a\r\nb", d.text())
    }

    @Test
    fun `brackets and quotes are closed automatically and typed over`() {
        val d = doc("")
        d.typeChar('f')
        d.typeChar('(')
        assertEquals("f()", d.text())
        assertEquals(TextPos(0, 2), d.caret)
        d.typeChar('"')
        assertEquals("f(\"\")", d.text())
        d.typeChar('x')
        d.typeChar('"')
        d.typeChar(')')
        assertEquals("f(\"x\")", d.text())
        assertEquals(TextPos(0, 6), d.caret)
    }

    @Test
    fun `no auto-close before a word and no quote pairing after a letter`() {
        val d = doc("word")
        d.typeChar('(')
        assertEquals("(word", d.text())
        val e = doc("don")
        e.moveToDocumentEnd()
        e.typeChar('\'')
        assertEquals("don'", e.text())
    }

    @Test
    fun `typing a bracket over a selection wraps it`() {
        val d = doc("value")
        d.selectAll()
        d.typeChar('[')
        assertEquals("[value]", d.text())
        assertEquals("value", d.selectedText())
    }

    @Test
    fun `backspace inside an empty pair removes both characters`() {
        val d = doc("")
        d.typeChar('{')
        d.backspace()
        assertEquals("", d.text())
    }

    @Test
    fun `matching bracket is found across lines in both directions`() {
        val d = doc("fun a() {\n    if (x) { y }\n}")
        d.moveTo(TextPos(0, 9))
        assertEquals(TextPos(0, 8) to TextPos(2, 0), d.matchingBracket())
        d.moveTo(TextPos(2, 1))
        assertEquals(TextPos(2, 0) to TextPos(0, 8), d.matchingBracket())
        d.moveTo(TextPos(1, 7))
        assertEquals(TextPos(1, 7) to TextPos(1, 9), d.matchingBracket())
        d.moveTo(TextPos(1, 2))
        assertEquals(null, d.matchingBracket())
    }

    @Test
    fun `identifier under the caret and its whole-word occurrences`() {
        val d = doc("val count = counter + count")
        assertEquals("count", d.identifierAt(TextPos(0, 6)))
        assertEquals(null, d.identifierAt(TextPos(0, 11)))
        assertEquals(
            listOf(4, 22),
            org.example.kotlineditor.state
                .wordOccurrences(d.lines[0], "count")!!
                .toList(),
        )
        assertEquals(
            null,
            org.example.kotlineditor.state
                .wordOccurrences("abc", "count"),
        )
    }

    @Test
    fun `reloading from disk is undoable and leaves the document saved`() {
        val d = doc("one\ntwo")
        d.moveTo(TextPos(1, 2))
        d.reload(listOf("uno", "dos", "tres"))
        assertEquals("uno\ndos\ntres", d.text())
        assertEquals(TextPos(1, 2), d.caret)
        assertFalse(d.isModified)
        d.undo()
        assertEquals("one\ntwo", d.text())
    }

    @Test
    fun `move lines up and down`() {
        val d = doc("a\nb\nc")
        d.moveTo(TextPos(0, 1))
        d.moveLines(1)
        assertEquals("b\na\nc", d.text())
        assertEquals(TextPos(1, 1), d.caret)
        d.moveLines(1)
        d.moveLines(1) // already last: no-op
        assertEquals("b\nc\na", d.text())
        d.select(TextPos(1, 0), TextPos(2, 1))
        d.moveLines(-1)
        assertEquals("c\na\nb", d.text())
        assertEquals("c\na", d.selectedText())
    }

    @Test
    fun `extend selection grows from word to line to block to document and shrinks back`() {
        val d = doc(textOf("fun f() {", "    val total = 1", "}", "val x = 2"))
        d.folding.regions =
            org.example.kotlineditor.state.FoldingModel
                .computeRegions(d.lines.toList(), Language.Kotlin)
        d.moveTo(TextPos(1, 10))
        d.extendSelection()
        assertEquals("total", d.selectedText())
        d.extendSelection()
        assertEquals("val total = 1", d.selectedText())
        d.extendSelection()
        assertEquals("fun f() {\n    val total = 1\n}", d.selectedText())
        d.extendSelection()
        assertEquals(d.text(), d.selectedText())
        d.shrinkSelection()
        d.shrinkSelection()
        assertEquals("val total = 1", d.selectedText())
        d.shrinkSelection()
        d.shrinkSelection()
        assertFalse(d.hasSelection)
        assertEquals(TextPos(1, 10), d.caret)
    }

    @Test
    fun `replace all occurrences in one undoable step`() {
        val d = doc("foo foo\nbar\nxfoo")
        d.replaceAll(d.findAll("foo"), 3, "baz")
        assertEquals("baz baz\nbar\nxbaz", d.text())
        d.undo()
        assertEquals("foo foo\nbar\nxfoo", d.text())
    }

    @Test
    fun `tabs count as four cells`() {
        assertEquals(5, visualColumn("\tx", 2))
        assertEquals(1, columnForVisual("\tx", 4))
        assertEquals(0, columnForVisual("\tx", 1))
    }
}

private fun textOf(vararg lines: String): String = lines.joinToString("\n")
