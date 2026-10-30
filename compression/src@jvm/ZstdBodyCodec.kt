package org.example.kotlineditor.compression

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdException
import java.io.IOException

/**
 * zstd level 3 with an optional dictionary for source code.
 *
 * The dictionary is tried only for [code] bodies and only when it makes the frame smaller than plain zstd:
 * this keeps it from degrading metadata or very large files, where a code dictionary does not help.
 */
class ZstdBodyCodec(
    dictionary: ByteArray? = Dictionaries.generic,
    level: Int = DEFAULT_LEVEL,
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val minSavingPercent: Int = DEFAULT_MIN_SAVING_PERCENT,
    private val dictionaryLimit: Int = DEFAULT_DICTIONARY_LIMIT,
    private val maxSize: Int = Int.MAX_VALUE,
) : BodyCodec {
    /** Identifier of the configured dictionary, or 0 when there is none. */
    val dictionaryId: Long = dictionary?.let { Zstd.getDictIdFromDict(it) } ?: 0L

    private val plainEncoder = ZstdCompressCtx().setLevel(level).setContentSize(true)
    private val plainDecoder = ZstdDecompressCtx()

    private val dictionaryEncoder =
        dictionary?.let {
            ZstdCompressCtx()
                .setLevel(level)
                .setContentSize(true)
                .setDictID(true)
                .loadDict(it)
        }

    private val dictionaryDecoder = dictionary?.let { ZstdDecompressCtx().loadDict(it) }

    init {
        require(dictionary == null || dictionaryId != 0L) {
            "A zstd dictionary must carry an identifier, otherwise frames cannot say which one they used"
        }
    }

    override fun compress(body: ByteArray, code: Boolean): ByteArray? {
        if (body.size < threshold) return null
        val plain = encode(plainEncoder, body)
        val best =
            if (code && dictionaryEncoder != null && body.size <= dictionaryLimit) {
                val withDictionary = encode(dictionaryEncoder, body)
                if (withDictionary.size < plain.size) withDictionary else plain
            } else {
                plain
            }
        val saves = best.size * PERCENT < body.size * (PERCENT - minSavingPercent)
        return if (saves) best else null
    }

    override fun decompress(body: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(body)
        if (size < 0 || size > maxSize) throw IOException("Invalid compressed frame size $size")
        return try {
            decoderMatchingDictionary(body).decompress(body, size.toInt())
        } catch (e: ZstdException) {
            throw IOException("zstd decompression failed", e)
        }
    }

    private fun decoderMatchingDictionary(body: ByteArray): ZstdDecompressCtx {
        val dictionaryId = Zstd.getDictIdFromFrame(body)
        return when {
            dictionaryId == 0L -> plainDecoder
            dictionaryId == this.dictionaryId && dictionaryDecoder != null -> dictionaryDecoder
            else -> throw IOException("Unknown zstd dictionary $dictionaryId")
        }
    }

    /** Dictionary identifier stored in [body], 0 when it was compressed without a dictionary. */
    fun dictionaryIdOf(frame: ByteArray): Long = Zstd.getDictIdFromFrame(frame)

    override fun close() {
        runCatching { plainEncoder.close() }
        runCatching { plainDecoder.close() }
        runCatching { dictionaryEncoder?.close() }
        runCatching { dictionaryDecoder?.close() }
    }

    private fun encode(encoder: ZstdCompressCtx, body: ByteArray): ByteArray =
        try {
            encoder.compress(body)
        } catch (e: ZstdException) {
            throw IOException("zstd compression failed", e)
        }

    private companion object {
        /** Level chosen by the benchmark: the level 9 gains do not justify its CPU cost for interactive use. */
        const val DEFAULT_LEVEL = 3

        /** Below this size a zstd frame cannot beat the raw body. */
        const val DEFAULT_THRESHOLD = 64

        /** Same minimum saving as the deflate codec that zstd replaces. */
        const val DEFAULT_MIN_SAVING_PERCENT = 10

        /** Above this size the dictionary does not help and costs a second compression. */
        const val DEFAULT_DICTIONARY_LIMIT = 1 shl 20

        const val PERCENT = 100
    }
}
