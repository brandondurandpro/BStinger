package org.example.kotlineditor.rpc

private const val BYTE_MASK = 0xFF
private const val INT_MASK = 0xFFFFFFFFL

/** Growable big-endian byte buffer. Strings are UTF-8 with an int length prefix (-1 means null). */
class WireWriter(initialCapacity: Int = DEFAULT_CAPACITY) {
    private var buffer = ByteArray(initialCapacity)

    /** Number of bytes written so far. */
    var size = 0
        private set

    /** Appends the low 8 bits of [value]. */
    fun byte(value: Int): WireWriter =
        apply {
            ensure(1)
            buffer[size++] = value.toByte()
        }

    /** Appends a boolean as one byte. */
    fun bool(value: Boolean): WireWriter = byte(if (value) 1 else 0)

    /** Appends the low 16 bits of [value]. */
    fun short(value: Int): WireWriter = bigEndian(value.toLong(), Short.SIZE_BYTES)

    /** Appends a 32-bit integer. */
    fun int(value: Int): WireWriter = bigEndian(value.toLong(), Int.SIZE_BYTES)

    /** Appends a 64-bit integer. */
    fun long(value: Long): WireWriter = bigEndian(value, Long.SIZE_BYTES)

    /** Appends a nullable UTF-8 string. */
    fun string(value: String?): WireWriter =
        apply {
            if (value == null) {
                int(-1)
            } else {
                val bytes = value.encodeToByteArray()
                int(bytes.size)
                ensure(bytes.size)
                bytes.copyInto(buffer, size)
                size += bytes.size
            }
        }

    /** Appends a byte array, with its length. */
    fun bytes(value: ByteArray): WireWriter =
        apply {
            int(value.size)
            ensure(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

    /** The bytes written so far. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun bigEndian(value: Long, bytes: Int): WireWriter =
        apply {
            ensure(bytes)
            for (index in bytes - 1 downTo 0) buffer[size++] = (value shr index * Byte.SIZE_BITS).toByte()
        }

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/** Reads what a [WireWriter] wrote, in the same order. */
class WireReader(private val buffer: ByteArray, private var position: Int = 0) {
    /** Reads one signed byte. */
    fun byte(): Int = buffer[position++].toInt()

    /** Reads a boolean. */
    fun bool(): Boolean = byte() != 0

    /** Reads an unsigned 16-bit value. */
    fun short(): Int = bigEndian(Short.SIZE_BYTES).toInt()

    /** Reads a 32-bit integer. */
    fun int(): Int = bigEndian(Int.SIZE_BYTES).toInt()

    /** Reads a 64-bit integer. */
    fun long(): Long {
        val high = int().toLong()
        val low = int().toLong() and INT_MASK
        return high shl Int.SIZE_BITS or low
    }

    /** Reads a nullable UTF-8 string. */
    fun stringOrNull(): String? {
        val length = int()
        return if (length < 0) {
            null
        } else {
            val value = buffer.decodeToString(position, position + length)
            position += length
            value
        }
    }

    /** Reads a string that must not be null. */
    fun string(): String = checkNotNull(stringOrNull()) { "Unexpected null string" }

    /** Reads a byte array written by [WireWriter.bytes]. */
    fun bytes(): ByteArray {
        val length = int()
        require(length in 0..buffer.size - position) { "Truncated byte array" }
        val value = buffer.copyOfRange(position, position + length)
        position += length
        return value
    }

    private fun bigEndian(bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = value shl Byte.SIZE_BITS or (buffer[position++].toLong() and BYTE_MASK.toLong()) }
        return value
    }
}
