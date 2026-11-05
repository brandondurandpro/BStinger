package org.example.kotlineditor.compression

import java.io.IOException

/**
 * Compresses and decompresses frame bodies.
 *
 * One instance belongs to one connection: the native contexts are not thread-safe, and loading the dictionary
 * once per connection is what keeps per-frame compression cheap.
 */
interface BodyCodec : AutoCloseable {
    /**
     * Compresses [body], or returns `null` when compression is not worth it.
     *
     * [code] tells whether the body is source code: only then may a dictionary be used, because a code
     * dictionary helps file contents and can hurt metadata such as paths, symbols or diffs.
     */
    fun compress(body: ByteArray, code: Boolean): ByteArray?

    /** Decompresses a body produced by [compress]. */
    fun decompress(body: ByteArray): ByteArray

    /**
     * Decompresses [body] unless it holds more than [maxSize] bytes: then throws an [IOException], before
     * allocating them when the codec can tell the size in advance.
     */
    fun decompress(body: ByteArray, maxSize: Int): ByteArray {
        val decompressed = decompress(body)
        if (decompressed.size > maxSize) throw IOException("Decompressed body too large (${decompressed.size})")
        return decompressed
    }

    override fun close() = Unit
}
