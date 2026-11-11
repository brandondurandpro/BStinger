package org.example.kotlineditor.state

import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.memory.ProcessMemory
import org.example.kotlineditor.model.ServerMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MB = 1024L * 1024L

class IdeMemoryTest {
    private val ui = ProcessIdentity(pid = 100, startedAtMillis = 5_000)

    private fun server(resident: Long, pid: Long = 200, startedAt: Long = 6_000) =
        ServerMemory(heapUsedBytes = 10 * MB, heapMaxBytes = 100 * MB, residentBytes = resident, pid, startedAt)

    @Test
    fun `a server in another process is added to the UI`() {
        val memory = ideMemory(ui, ProcessMemory(privateBytes = 250 * MB, residentBytes = 300 * MB), server(120 * MB))
        assertFalse(memory.sameProcess)
        assertEquals(300 * MB, memory.uiBytes)
        assertEquals(120 * MB, memory.serverBytes)
        assertEquals(420 * MB, memory.totalBytes)
    }

    @Test
    fun `the total is the sum of the megabytes shown for each`() {
        // 300.9 MB and 120.9 MB are shown as 300 MB and 120 MB: 421 MB beside them would look wrong.
        val uiBytes = 300 * MB + 9 * MB / 10
        val serverBytes = 120 * MB + 9 * MB / 10
        val memory = ideMemory(ui, ProcessMemory(privateBytes = 0, residentBytes = uiBytes), server(serverBytes))
        assertEquals("420 MB", memoryText(checkNotNull(memory.totalBytes)))
    }

    @Test
    fun `gigabytes are written with a point in every locale`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        try {
            assertEquals("1.5 GB", memoryText(1536 * MB))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `a server in the process of the UI is counted once`() {
        val same = server(640 * MB, pid = ui.pid, startedAt = ui.startedAtMillis)
        val memory = ideMemory(ui, ProcessMemory(privateBytes = 500 * MB, residentBytes = 641 * MB), same)
        assertTrue(memory.sameProcess)
        assertEquals(641 * MB, memory.totalBytes)
    }

    @Test
    fun `the same pid started at another time is another process, on another machine`() {
        val memory = ideMemory(ui, ProcessMemory(0, 300 * MB), server(120 * MB, pid = ui.pid, startedAt = 9_000))
        assertFalse(memory.sameProcess)
        assertEquals(420 * MB, memory.totalBytes)
    }

    @Test
    fun `an older server that names no process is another process`() {
        val unknown = ProcessIdentity(pid = 0, startedAtMillis = 0)
        val memory = ideMemory(unknown, ProcessMemory(0, 300 * MB), server(120 * MB, pid = 0, startedAt = 0))
        assertFalse(memory.sameProcess)
    }

    @Test
    fun `no total when a part cannot be measured`() {
        val noUi = ideMemory(ui, uiMemory = null, server(120 * MB))
        assertNull(noUi.uiBytes)
        assertNull(noUi.totalBytes)
        val noServer = ideMemory(ui, ProcessMemory(0, 300 * MB), server(resident = 0))
        assertNull(noServer.serverBytes)
        assertNull(noServer.totalBytes)
    }
}
