package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiStyleTest {
    @Test
    fun `a plain line has no spans`() {
        val line = AnsiParser().parse("plain")
        assertEquals("plain", line.text)
        assertTrue(line.spans.isEmpty())
    }

    @Test
    fun `a colored part becomes a span and the escapes leave the text`() {
        val line = AnsiParser().parse("a \u001B[32mgreen\u001B[0m b")
        assertEquals("a green b", line.text)
        val span = line.spans.single()
        assertEquals(2, span.start)
        assertEquals(7, span.end)
        assertEquals(0xFF0DBC79.toInt(), span.color)
    }

    @Test
    fun `a color stays on across lines until it is reset`() {
        val parser = AnsiParser()
        parser.parse("\u001B[1m\u001B[36mstart")
        val next = parser.parse("still")
        assertTrue(next.spans.single().bold)
        assertEquals(5, next.spans.single().end)
        assertTrue(parser.parse("\u001B[0mdone").spans.isEmpty())
    }

    @Test
    fun `palette and true colors are read, other sequences are dropped`() {
        val line = AnsiParser().parse("\u001B[2K\u001B[38;2;10;20;30mx\u001B[38;5;196my")
        assertEquals("xy", line.text)
        assertEquals(0xFF0A141E.toInt(), line.spans[0].color)
        assertEquals(0xFFFF0000.toInt(), line.spans[1].color)
    }
}
