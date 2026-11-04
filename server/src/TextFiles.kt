package org.example.kotlineditor.server

import org.example.kotlineditor.model.FileContent
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
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
        if (isBinary(bytes)) FileContent.Binary else splitLines(utf8OrNull(bytes) ?: String(bytes, Charsets.ISO_8859_1))

    // The text of [bytes] if they are valid UTF-8.
    private fun utf8OrNull(bytes: ByteArray): CharSequence? {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * The whole text of the file at [path], line endings included, in its charset ([charsetOf]): what an agent reads
     * and edits, and gives back to [encode].
     */
    fun readText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        return String(bytes, charsetOf(bytes))
    }

    /**
     * The charset [bytes] are read and written with: UTF-8 when they are valid UTF-8, ISO-8859-1 otherwise (older
     * projects in Latin-1 or Windows-1252). ISO-8859-1 maps each byte to one character, so that a file saved without
     * changes keeps every byte, whatever its real encoding.
     */
    fun charsetOf(bytes: ByteArray): Charset = if (utf8OrNull(bytes) != null) Charsets.UTF_8 else Charsets.ISO_8859_1

    /**
     * The bytes of [text] in the charset of the file it replaces ([existing], `null` for a new file); fails rather
     * than replace a character that charset cannot hold.
     */
    fun encode(text: String, existing: ByteArray?): ByteArray {
        val charset = if (existing == null) Charsets.UTF_8 else charsetOf(existing)
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded =
            try {
                encoder.encode(CharBuffer.wrap(text))
            } catch (e: CharacterCodingException) {
                throw IOException("The file is in ${charset.name()}, which cannot hold every character typed", e)
            }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
}
