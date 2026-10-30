package org.example.kotlineditor.benchmark

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import org.example.kotlineditor.rpc.WireReader
import org.example.kotlineditor.rpc.WireWriter
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Bytes of a frame outside its body: length, kind, flags, id. */
const val FRAME_HEADER = 10

/** Same threshold as the deflate codec the protocol used before zstd. */
const val DEFLATE_THRESHOLD = 16 * 1024

/** Same minimum saving as the production codec (zstd in the `compression` module). */
const val MIN_SAVING_PERCENT = 10

/** Below this size a zstd frame cannot beat the raw body: do not even try. */
const val ZSTD_THRESHOLD = 64

private const val PERCENT = 100
private const val SIZE_PREFIX = Int.SIZE_BYTES
private const val CHUNK = 64 * 1024
private const val EXPECTED_RATIO = 3

/** What a body contains: source code (dictionary-friendly) or metadata (paths, symbols, diffs). */
enum class PayloadKind {
    /** A file content, the only kind a language dictionary is trained for. */
    CODE,

    /** A request, a path list, a diff, a shell event: a dictionary does not help and may hurt. */
    METADATA,
}

/** A body as it would go on the wire, and whether it is compressed. */
class Encoded(
    /** Bytes after the frame header. */
    val data: ByteArray,
    /** Whether [data] is compressed. */
    val compressed: Boolean,
    /** Whether [data] was compressed with a dictionary. */
    val dictionary: Boolean = false,
)

/** A body codec: what the protocol does before writing, and after reading. */
interface Codec : AutoCloseable {
    /** Name shown in the report. */
    val name: String

    /** Encodes one body, applying the protocol's threshold and minimum-saving rules. */
    fun encode(body: ByteArray, kind: PayloadKind = PayloadKind.METADATA): Encoded

    /** Decodes a body produced by [encode]. */
    fun decode(encoded: Encoded): ByteArray

    override fun close() = Unit
}

/** The codec the protocol used before zstd: raw deflate (BEST_SPEED) for bodies of at least 16 KB. */
class CurrentDeflate : Codec {
    override val name = "deflate-1 (actuel)"

    override fun encode(body: ByteArray, kind: PayloadKind): Encoded {
        if (body.size < DEFLATE_THRESHOLD) return Encoded(body, false)
        val compressed = deflate(body)
        val saves = compressed.size * PERCENT < body.size * (PERCENT - MIN_SAVING_PERCENT)
        return if (saves) Encoded(compressed, true) else Encoded(body, false)
    }

    override fun decode(encoded: Encoded): ByteArray = if (encoded.compressed) inflate(encoded.data) else encoded.data

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / EXPECTED_RATIO)
            val chunk = ByteArray(CHUNK)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            return WireWriter(SIZE_PREFIX + out.size()).int(data.size).toByteArray() + out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val size = WireReader(data).int()
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, SIZE_PREFIX, data.size - SIZE_PREFIX)
            val out = ByteArray(size)
            var done = 0
            var stalled = false
            while (done < size && !stalled) {
                val n = inflater.inflate(out, done, size - done)
                stalled = n == 0 && (inflater.finished() || inflater.needsInput())
                done += n
            }
            check(done == size) { "truncated deflate body" }
            return out
        } finally {
            inflater.end()
        }
    }
}

/**
 * Zstandard with an optional dictionary. Contexts are created once per codec, as a real implementation would do:
 * loading a dictionary on every frame would dominate the measurement.
 */
class ZstdCodec(
    override val name: String,
    level: Int,
    dictionary: ByteArray? = null,
    /** Whether the frame carries the dictionary id (4 bytes per frame). */
    private val dictionaryId: Boolean = true,
) : Codec {
    private val hasDictionary = dictionary != null

    private val encoder =
        ZstdCompressCtx()
            .setLevel(level)
            .setContentSize(true)
            .setDictID(dictionaryId)
            .apply { if (dictionary != null) loadDict(dictionary) }

    private val decoder =
        ZstdDecompressCtx()
            .apply { if (dictionary != null) loadDict(dictionary) }

    override fun encode(body: ByteArray, kind: PayloadKind): Encoded {
        if (body.size < ZSTD_THRESHOLD) return Encoded(body, false)
        val compressed = encoder.compress(body)
        val saves = compressed.size * PERCENT < body.size * (PERCENT - MIN_SAVING_PERCENT)
        return if (saves) Encoded(compressed, true, hasDictionary) else Encoded(body, false)
    }

    override fun decode(encoded: Encoded): ByteArray {
        if (!encoded.compressed) return encoded.data
        val size = Zstd.getFrameContentSize(encoded.data)
        check(size in 0..Int.MAX_VALUE.toLong()) { "zstd frame has no content size" }
        return decoder.decompress(encoded.data, size.toInt())
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }
}

/**
 * Uses the dictionary only for [PayloadKind.CODE] bodies. Metadata (paths, symbols, diffs, shell output) is
 * compressed without it, because a code dictionary can make those larger.
 */
class HybridZstdCodec(
    override val name: String,
    level: Int,
    dictionary: ByteArray,
) : Codec {
    private val withDictionary = ZstdCodec("$name (code)", level, dictionary)
    private val withoutDictionary = ZstdCodec("$name (métadonnées)", level)

    override fun encode(body: ByteArray, kind: PayloadKind): Encoded =
        if (kind == PayloadKind.CODE) withDictionary.encode(body, kind) else withoutDictionary.encode(body, kind)

    override fun decode(encoded: Encoded): ByteArray =
        if (encoded.dictionary) withDictionary.decode(encoded) else withoutDictionary.decode(encoded)

    override fun close() {
        withDictionary.close()
        withoutDictionary.close()
    }
}

/** Median of [values]; used to keep GC and JIT pauses out of the reported time. */
fun median(values: LongArray): Double {
    val sorted = values.sortedArray()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle].toDouble()
}
