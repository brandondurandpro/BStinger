package org.example.kotlineditor.server

import org.example.kotlineditor.model.FileContent
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Reading text files: binary detection, decoding and line splitting. */
object TextFiles {
    /** Files larger than this are not opened (32 MB). */
    const val DEFAULT_MAX_SIZE = 32L * 1024 * 1024

    private const val BINARY_PROBE = 8000
    private const val AVERAGE_LINE = 32

    /** Reads [path] as text unless it is binary or larger than [maxSize]. Never throws. */
    fun read(path: Path, maxSize: Long): FileContent =
        try {
            val size = Files.size(path)
            if (size > maxSize) FileContent.TooLarge(size) else decode(Files.readAllBytes(path))
        } catch (e: IOException) {
            FileContent.Error(e.message ?: e::class.simpleName.orEmpty())
        }

    /** A file is binary when its first bytes contain a NUL, like Git decides. */
    fun isBinary(bytes: ByteArray): Boolean = (0 until minOf(bytes.size, BINARY_PROBE)).any { bytes[it] == 0.toByte() }

    /** Splits [text] into lines, remembering whether the file used CRLF. */
    fun splitLines(text: CharSequence): FileContent.Text {
        val lines = ArrayList<String>(text.length / AVERAGE_LINE + 1)
        var crlf = false
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                val cr = i > start && text[i - 1] == '\r'
                crlf = crlf || cr
                lines += text.substring(start, if (cr) i - 1 else i)
                start = i + 1
            }
        }
        lines += text.substring(start)
        return FileContent.Text(lines, if (crlf) "\r\n" else "\n")
    }

    private fun decode(bytes: ByteArray): FileContent =
        if (isBinary(bytes)) {
            FileContent.Binary
        } else {
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
            splitLines(text)
        }
}
