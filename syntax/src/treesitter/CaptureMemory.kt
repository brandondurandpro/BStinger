package org.example.kotlineditor.syntax.treesitter

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator

/**
 * The native memory of the captures of one run of a query. jtreesitter allocates a few small structs for each
 * capture it returns (the match, its index, a node per capture of the match), which are read at once and never
 * again. They are taken from one block, used again from its start for the next capture ([recycle]), rather than a
 * `malloc` each: a large file has hundreds of thousands of captures, and that many small blocks, once freed, stay
 * in the allocator of the thread that asked for them instead of going back to the system (gigabytes, over the
 * threads that colored a large diff).
 *
 * Not thread-safe, and closed by the thread that created it: one run of a query uses it.
 */
internal class CaptureMemory(private val blockBytes: Long = BLOCK_BYTES) :
    SegmentAllocator,
    AutoCloseable {
    private val arena = Arena.ofConfined()
    private val block = arena.allocate(blockBytes, ALIGNMENT)
    private var used = 0L

    override fun allocate(byteSize: Long, byteAlignment: Long): MemorySegment {
        val start = (used + byteAlignment - 1) / byteAlignment * byteAlignment
        val end = start + byteSize
        val fitsBlock = end <= blockBytes && byteAlignment <= ALIGNMENT
        // A capture larger than the block (a match of hundreds of nodes): allocated apart, freed by [close].
        if (!fitsBlock) return arena.allocate(byteSize, byteAlignment)
        used = end
        return block.asSlice(start, byteSize).fill(0)
    }

    /** Gives the block back: what was allocated since the last call must not be read anymore. */
    fun recycle() {
        used = 0
    }

    override fun close() = arena.close()

    private companion object {
        // A match and its index take about 30 bytes, each of its nodes 32: room for a match of hundreds of nodes.
        const val BLOCK_BYTES = 16_384L

        // The strictest alignment of the structs of tree-sitter, which hold pointers and 32-bit integers.
        const val ALIGNMENT = 8L
    }
}
