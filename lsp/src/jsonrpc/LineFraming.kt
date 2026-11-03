package org.example.kotlineditor.lsp.jsonrpc

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Newline-delimited JSON, the stdio transport of the Agent Client Protocol: each message is one line of UTF-8 JSON
 * (JSON escapes the newlines of its strings). Blank lines are skipped, a CR before the LF is dropped, and a line
 * longer than the cap is refused before it fills the memory.
 */
object LineFraming : MessageFraming {
    private const val LF = '\n'.code
    private const val CR = '\r'.code.toByte()

    override fun write(output: OutputStream, body: ByteArray) {
        output.write(body)
        output.write(LF)
        output.flush()
    }

    override fun read(input: InputStream, maxSize: Int): ByteArray? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            when {
                byte < 0 && line.size() == 0 -> return null
                byte < 0 -> throw LspFramingException("Stream ended inside a message")
                byte != LF -> {
                    if (line.size() >= maxSize) {
                        throw LspFramingException("Message larger than the $maxSize bytes allowed")
                    }
                    line.write(byte)
                }
                else -> {
                    val body = withoutCarriageReturn(line.toByteArray())
                    if (body.any { !it.toInt().toChar().isWhitespace() }) return body
                    line.reset()
                }
            }
        }
    }

    private fun withoutCarriageReturn(bytes: ByteArray): ByteArray {
        val endsWithCr = bytes.isNotEmpty() && bytes.last() == CR
        return if (endsWithCr) bytes.copyOf(bytes.size - 1) else bytes
    }
}
