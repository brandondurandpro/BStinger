package org.example.kotlineditor.lsp.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Parsing of the `/proc` files, from fixed contents: runs on every OS. */
class ProcFilesTest {
    private val status =
        """
        Name:	java
        Umask:	0022
        State:	S (sleeping)
        VmPeak:	 9140448 kB
        VmRSS:	  412340 kB
        RssAnon:	  301204 kB
        RssFile:	  110000 kB
        Threads:	42
        """.trimIndent()

    @Test
    fun `status gives the anonymous and the whole resident memory`() {
        val memory = parseProcStatus(status.toByteArray())
        assertEquals(ProcessMemory(privateBytes = 301_204L * 1024, residentBytes = 412_340L * 1024), memory)
    }

    @Test
    fun `status of an exited process has no memory`() {
        val zombie =
            """
            Name:	zombie
            State:	Z (zombie)
            """.trimIndent()
        assertNull(parseProcStatus(zombie.toByteArray()))
        assertNull(parseProcStatus(ByteArray(0)))
    }

    @Test
    fun `start time is field 22 even when the name holds spaces and parentheses`() {
        val fields = (3..52).joinToString(" ") { if (it == 22) "987654" else it.toString() }
        assertEquals(987_654L, parseProcStatStartTime("1234 (my (odd) name) $fields\n".toByteArray()))
        assertEquals(987_654L, parseProcStatStartTime("1234 (php) $fields".toByteArray()))
    }

    @Test
    fun `a truncated stat has no start time`() {
        assertNull(parseProcStatStartTime("1234 (php) S 1 2".toByteArray()))
        assertNull(parseProcStatStartTime("garbage".toByteArray()))
    }

    @Test
    fun `smaps_rollup gives every value in bytes`() {
        val rollup =
            """
            55d0c0000000-7ffd1c1f2000 ---p 00000000 00:00 0                          [rollup]
            Rss:              412340 kB
            Pss:              400100 kB
            Private_Clean:     10000 kB
            Private_Dirty:    300000 kB
            Swap:                  0 kB
            """.trimIndent()
        val values = parseSmapsRollup(rollup.toByteArray())
        assertEquals(400_100L * 1024, values["Pss"])
        assertEquals(300_000L * 1024, values["Private_Dirty"])
        assertEquals(0L, values["Swap"])
        assertEquals(setOf("Rss", "Pss", "Private_Clean", "Private_Dirty", "Swap"), values.keys)
    }
}
