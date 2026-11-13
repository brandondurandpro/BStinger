package org.example.kotlineditor.state

import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.model.ServerMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeCpuTest {
    private val ui = ProcessIdentity(pid = 100, startedAtMillis = 5_000)

    private fun server(cpu: Int, pid: Long = 200, startedAt: Long = 6_000) =
        ServerMemory(heapUsedBytes = 1, heapMaxBytes = 2, residentBytes = 3, pid, startedAt, cpuPercent = cpu)

    @Test
    fun `a server in another process is added to the UI`() {
        val cpu = ideCpu(ui, uiPercent = 30, server(cpu = 12))
        assertFalse(cpu.sameProcess)
        assertEquals(42, cpu.totalPercent)
    }

    @Test
    fun `more than one core reads above 100`() {
        assertEquals(250, ideCpu(ui, 150, server(100)).totalPercent)
    }

    @Test
    fun `a server in the process of the UI is counted once`() {
        val same = server(cpu = 12, pid = ui.pid, startedAt = ui.startedAtMillis)
        val cpu = ideCpu(ui, uiPercent = 30, same)
        assertTrue(cpu.sameProcess)
        assertEquals(30, cpu.totalPercent)
    }

    @Test
    fun `the process of the UI unmeasured leaves the server's measure for the one process`() {
        val same = server(cpu = 12, pid = ui.pid, startedAt = ui.startedAtMillis)
        assertEquals(12, ideCpu(ui, uiPercent = null, same).totalPercent)
    }

    @Test
    fun `no total when a part cannot be measured`() {
        assertNull(ideCpu(ui, uiPercent = null, server(12)).totalPercent)
        val notMeasured = server(ServerMemory.NOT_MEASURED)
        val cpu = ideCpu(ui, uiPercent = 30, notMeasured)
        assertNull(cpu.serverPercent)
        assertNull(cpu.totalPercent)
    }

    @Test
    fun `an older server that names no process is another process`() {
        val unknown = ProcessIdentity(pid = 0, startedAtMillis = 0)
        assertFalse(ideCpu(unknown, 30, server(12, pid = 0, startedAt = 0)).sameProcess)
    }
}
