package org.example.kotlineditor.rpc

import java.io.OutputStream

/**
 * The bytes written to it, kept in chunks until [toByteArray] copies them once into an array of their exact size.
 * A `ByteArrayOutputStream` copies what it holds each time it grows, then again to give its array: three times the
 * bytes of a large message, where this allocates twice.
 */
internal class EncodedBytes : OutputStream() {
    private val full = ArrayList<ByteArray>()
    private var chunk = ByteArray(FIRST_CHUNK_BYTES)
    private var used = 0
    private var filled = 0L

    override fun write(byte: Int) {
        if (used == chunk.size) nextChunk()
        chunk[used] = byte.toByte()
        used++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var from = offset
        var remaining = length
        while (remaining > 0) {
            if (used == chunk.size) nextChunk()
            val count = minOf(remaining, chunk.size - used)
            bytes.copyInto(chunk, used, from, from + count)
            used += count
            from += count
            remaining -= count
        }
    }

    /** What was written, in one array. */
    fun toByteArray(): ByteArray {
        val size = filled + used
        require(size <= Int.MAX_VALUE) { "Message of $size bytes" }
        val bytes = ByteArray(size.toInt())
        var at = 0
        for (part in full) {
            part.copyInto(bytes, at)
            at += part.size
        }
        chunk.copyInto(bytes, at, 0, used)
        return bytes
    }

    // Small messages fit the first chunk; the next ones double up to a size that large messages fill many times.
    private fun nextChunk() {
        full += chunk
        filled += chunk.size
        chunk = ByteArray(minOf(chunk.size * 2, MAX_CHUNK_BYTES))
        used = 0
    }

    private companion object {
        const val FIRST_CHUNK_BYTES = 1_024
        const val MAX_CHUNK_BYTES = 1_048_576
    }
}
