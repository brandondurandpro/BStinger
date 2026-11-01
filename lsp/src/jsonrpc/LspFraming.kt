package org.example.kotlineditor.lsp.jsonrpc

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A message that breaks the LSP base protocol: bad header, bad length, cut in the middle, too large. */
class LspFramingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The LSP base protocol: each message is `Content-Length: N` (other headers, such as `Content-Type`, are ignored),
 * an empty line, then N bytes of UTF-8 JSON. Headers are read byte by byte from a buffered stream, without regex;
 * a declared length above the cap is refused before anything is allocated for it.
 */
object LspFraming {
    /** Largest message accepted by default: a language server answering more is broken or hostile. */
    const val DEFAULT_MAX_MESSAGE_SIZE = 64 * 1024 * 1024

    private const val MAX_HEADER_LINE = 1024
    private const val SHOWN_HEADER = 80
    private const val INVALID_LENGTH = -2
    private const val CONTENT_LENGTH = "content-length"
    private const val CR = '\r'.code
    private const val LF = '\n'.code

    /** Writes and flushes one message; callers serialize writes to the same stream. */
    fun write(output: OutputStream, body: ByteArray) {
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /**
     * Reads one message body, blocking. Null when the stream ends cleanly between two messages (the server
     * exited); [LspFramingException] when it ends inside one or when the message breaks the protocol.
     */
    fun read(input: InputStream, maxSize: Int = DEFAULT_MAX_MESSAGE_SIZE): ByteArray? {
        var length = -1
        var first = true
        while (true) {
            val line = readHeaderLine(input, atMessageStart = first) ?: return null
            first = false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            ensure(colon > 0) { "Not an LSP header: ${line.take(SHOWN_HEADER)}" }
            if (line.substring(0, colon).trim().equals(CONTENT_LENGTH, ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toIntOrNull() ?: INVALID_LENGTH
                ensure(length >= 0) { "Invalid $line" }
            }
        }
        ensure(length >= 0) { "Message without Content-Length" }
        ensure(length <= maxSize) { "Message of $length bytes is larger than the $maxSize bytes allowed" }
        val body = input.readNBytes(length)
        ensure(body.size == length) { "Stream ended inside a message" }
        return body
    }

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw LspFramingException(message())
    }

    // One header line without its CR LF; null on a clean end of stream before the first byte of a message.
    private fun readHeaderLine(input: InputStream, atMessageStart: Boolean): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            when {
                byte < 0 && atMessageStart && line.isEmpty() -> return null
                byte < 0 -> throw LspFramingException("Stream ended inside a header")
                byte == LF -> return line.toString().removeSuffix(CR.toChar().toString())
                line.length >= MAX_HEADER_LINE -> throw LspFramingException("Header line too long")
                else -> line.append(byte.toChar())
            }
        }
    }
}
