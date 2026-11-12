package org.example.kotlineditor.e2e

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout

/**
 * What the allocator of the C library holds from the system, in use or freed but kept, over all its arenas
 * (`mallinfo2` of glibc): the native memory of tree-sitter, Skia and the codecs, which the Java heap does not show.
 */
object NativeMemory {
    private const val FIELDS = 10L
    private const val ARENA_FIELD = 0L
    private const val MAPPED_FIELD = 4L
    private const val IN_USE_FIELD = 7L
    private const val BYTES_PER_MB = 1_048_576L

    private val mallinfo by lazy { find() }

    /** The megabytes held, or `null` where the C library has no `mallinfo2` (Windows, macOS, an old glibc). */
    fun heldMegabytes(): Long? = megabytes(ARENA_FIELD, MAPPED_FIELD)

    /** The megabytes in use, that is not freed yet; `null` like [heldMegabytes]. */
    fun inUseMegabytes(): Long? = megabytes(IN_USE_FIELD, MAPPED_FIELD)

    private fun megabytes(first: Long, second: Long): Long? {
        val call = mallinfo ?: return null
        return Arena.ofConfined().use { arena ->
            val info = call.invokeWithArguments(arena as SegmentAllocator) as MemorySegment
            val bytes = info.getAtIndex(ValueLayout.JAVA_LONG, first) + info.getAtIndex(ValueLayout.JAVA_LONG, second)
            bytes / BYTES_PER_MB
        }
    }

    // `struct mallinfo2 mallinfo2(void)`: ten `size_t`, of which the bytes of the heaps, of the mapped blocks and in use.
    private fun find(): java.lang.invoke.MethodHandle? {
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find("mallinfo2").orElse(null) ?: return null
        val fields = MemoryLayout.sequenceLayout(FIELDS, ValueLayout.JAVA_LONG)
        return linker.downcallHandle(symbol, FunctionDescriptor.of(MemoryLayout.structLayout(fields)))
    }
}
