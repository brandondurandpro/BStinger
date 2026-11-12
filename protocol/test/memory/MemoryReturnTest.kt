package org.example.kotlineditor.memory

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryReturnTest {
    private val status = Path.of("/proc/self/status")

    private fun residentMegabytes(): Long {
        val memory = parseProcStatus(Files.readAllBytes(status))
        return (memory?.residentBytes ?: 0L) / BYTES_PER_MB
    }

    /**
     * Many small blocks freed while one allocated after them stays: the allocator cannot shrink its heap, the
     * case of the captures of tree-sitter or of the paragraphs of Skia, and keeps the memory until a trim.
     */
    @Test
    fun `freed native memory goes back to the system on a trim`() {
        val memory = MemoryReturn()
        if (!memory.canTrim || !Files.isReadable(status)) return // glibc only.
        Arena.ofConfined().use { survivor ->
            Arena.ofConfined().use { freed ->
                repeat(BLOCKS) { freed.allocate(BLOCK_BYTES).set(ValueLayout.JAVA_BYTE, 0, 1) }
                survivor.allocate(BLOCK_BYTES)
            }
            val kept = residentMegabytes()
            assertTrue(memory.trimNow(), "the trim gave nothing back")
            val given = kept - residentMegabytes()
            println("PERF ${"native memory given back by a trim".padEnd(50)} $given MB of $ALLOCATED_MB MB freed")
            assertTrue(given >= ALLOCATED_MB / 2, "the trim gave back $given MB of the $ALLOCATED_MB MB freed")
        }
    }

    @Test
    fun `a JVM that did not collect for a while does, unless it was told otherwise`() {
        val memory = MemoryReturn(idleGcIntervalMs = 60_000)
        val before = memory.idleGcInterval() ?: return // Another collector than G1.
        memory.start().use {
            val expected = if (before == 0L) 60_000L else before
            assertEquals(expected, memory.idleGcInterval())
        }
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
        const val BLOCK_BYTES = 256L
        const val BLOCKS = 600_000
        const val ALLOCATED_MB = BLOCKS * BLOCK_BYTES / BYTES_PER_MB
    }
}
