package org.example.kotlineditor.lsp.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ring buffer of samples. */
class MemoryHistoryTest {
    @Test
    fun `an empty history has no latest nor peak sample`() {
        val history = MemoryHistory(3)
        assertEquals(0, history.size)
        assertNull(history.latest)
        assertNull(history.peak)
        assertEquals(emptyList(), history.snapshot())
    }

    @Test
    fun `the oldest samples are dropped once the buffer is full`() {
        val history = MemoryHistory(3)
        for (time in 1L..5L) history.add(time, privateBytes = time * 10, residentBytes = time * 20, processCount = 1)
        assertEquals(3, history.size)
        assertEquals(listOf(3L, 4L, 5L), history.snapshot().map { it.timeMillis })
        assertEquals(MemorySample(5, 50, 100, 1), history.latest)
    }

    @Test
    fun `the peak is the first sample with the highest private memory`() {
        val history = MemoryHistory(10)
        listOf(10L, 70L, 30L, 70L, 20L).forEachIndexed { index, bytes -> history.add(index.toLong(), bytes, 0, 2) }
        assertEquals(1L, history.peak?.timeMillis)
    }

    @Test
    fun `one hour of history is the default`() = assertEquals(1800, MemoryHistory().capacity)
}
