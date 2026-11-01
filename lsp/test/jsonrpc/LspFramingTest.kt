package org.example.kotlineditor.lsp.jsonrpc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The Content-Length framing of the LSP base protocol. */
class LspFramingTest {
    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray())

    // Hands out one byte per read, as a pipe may: headers and bodies arrive split anywhere.
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val byte = if (length == 0) -1 else read()
            if (byte >= 0) buffer[offset] = byte.toByte()
            return if (length == 0) {
                0
            } else if (byte < 0) {
                -1
            } else {
                1
            }
        }
    }

    @Test
    fun `a message is written and read back`() {
        val out = ByteArrayOutputStream()
        LspFraming.write(out, """{"a":"é"}""".toByteArray())
        assertEquals("Content-Length: 10\r\n\r\n" + """{"a":"é"}""", out.toString(Charsets.UTF_8))
        assertEquals("""{"a":"é"}""", LspFraming.read(ByteArrayInputStream(out.toByteArray()))?.decodeToString())
    }

    @Test
    fun `several messages in one read, split anywhere, with extra headers`() {
        val crlf = "\r\n"
        val text =
            "Content-Length: 2$crlf$crlf{}" +
                "Content-Type: application/vscode-jsonrpc; charset=utf-8${crlf}content-length: 7$crlf$crlf[1,2,3]"
        for (input in listOf(stream(text), Trickle(text.toByteArray()))) {
            assertEquals("{}", LspFraming.read(input)?.decodeToString())
            assertEquals("[1,2,3]", LspFraming.read(input)?.decodeToString())
            assertNull(LspFraming.read(input))
        }
    }

    @Test
    fun `a message larger than the cap is refused before it is read`() {
        val header = stream("Content-Length: 83886080\r\n\r\n")
        val failure = assertFailsWith<LspFramingException> { LspFraming.read(header) }
        assertContains(failure.message.orEmpty(), "larger than")
    }

    @Test
    fun `broken framing is reported`() {
        val broken =
            mapOf(
                "Content-Length: abc\r\n\r\n" to "Invalid",
                "Content-Type: x\r\n\r\n{}" to "without Content-Length",
                "Starting the server...\n" to "Not an LSP header",
                "Content-Length: 10\r\n\r\n" + """{"a"""" to "inside a message",
                "Content-Len" to "inside a header",
            )
        for ((text, expected) in broken) {
            val failure = assertFailsWith<LspFramingException>(text) { LspFraming.read(stream(text)) }
            assertContains(failure.message.orEmpty(), expected)
        }
    }

    @Test
    fun `a clean end of stream between messages is not an error`() = assertNull(LspFraming.read(stream("")))
}
