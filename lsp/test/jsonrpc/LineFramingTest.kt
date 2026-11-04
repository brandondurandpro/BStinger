package org.example.kotlineditor.lsp.jsonrpc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Newline-delimited JSON, the stdio framing of ACP. */
class LineFramingTest {
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `a message is one line`() {
        val out = ByteArrayOutputStream()
        LineFraming.write(out, """{"a":"\n é"}""".toByteArray())
        assertEquals("""{"a":"\n é"}""" + "\n", out.toString(Charsets.UTF_8))
        assertEquals("""{"a":"\n é"}""", LineFraming.read(ByteArrayInputStream(out.toByteArray()))?.decodeToString())
    }

    @Test
    fun `messages follow each other, blank lines and CR are skipped`() {
        val input = stream(listOf("{}\r", "", "  ", "[1]").joinToString("\n", postfix = "\n"))
        assertEquals("{}", LineFraming.read(input)?.decodeToString())
        assertEquals("[1]", LineFraming.read(input)?.decodeToString())
        assertNull(LineFraming.read(input))
    }

    @Test
    fun `a stream that ends inside a message is an error`() {
        val failure = assertFailsWith<LspFramingException> { LineFraming.read(stream("{\"a\"")) }
        assertContains(failure.message.orEmpty(), "ended")
    }

    @Test
    fun `a line longer than the cap is refused`() {
        assertFailsWith<LspFramingException> { LineFraming.read(stream("x".repeat(100) + "\n"), maxSize = 10) }
    }
}
