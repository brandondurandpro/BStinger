package org.example.kotlineditor.unit

import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.model.NetworkRecord
import org.example.kotlineditor.rpc.WireWriter
import org.example.kotlineditor.state.NetworkView
import org.example.kotlineditor.state.formatBytes
import org.example.kotlineditor.state.hexDump
import org.example.kotlineditor.state.wireStrings
import kotlin.test.Test
import kotlin.test.assertEquals

private val CODE =
    """
    fun main() {
        println("é")
    }
    """.trimIndent()

class NetworkViewTest {
    private fun record(seq: Long, kind: String, method: String? = null) =
        NetworkRecord(seq, 0, Direction.Sent, kind, seq, method, 0, 14, ByteArray(0))

    @Test
    fun `the strings of a wire payload are found between the other fields`() {
        val body =
            WireWriter()
                .short(2)
                .string("/home/me/project/App.kt")
                .long(42)
                .bool(true)
                .string(CODE)
                .bytes(byteArrayOf(0, 1, 2, -1))
                .toByteArray()
        assertEquals(listOf("/home/me/project/App.kt", CODE), wireStrings(body))
        assertEquals(emptyList(), wireStrings(ByteArray(0)))
    }

    @Test
    fun `hex dump shows offsets, bytes and printable characters`() {
        val lines = hexDump("Hello, network!\u0000xyz".encodeToByteArray())
        assertEquals(2, lines.size)
        assertEquals("00000000  48 65 6c 6c 6f 2c 20 6e 65 74 77 6f 72 6b 21 00  Hello, network!.", lines[0])
        assertEquals("00000010  78 79 7a" + " ".repeat(39) + "  xyz", lines[1])
    }

    @Test
    fun `sizes are human readable`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun `filters hide heartbeats and keep the matching kinds and methods`() {
        val view = NetworkView(null)
        val records =
            listOf(record(0, "Ping"), record(1, "Request", "Read"), record(2, "Response", "Read"), record(3, "Event"))
        assertEquals(listOf(1L, 2L, 3L), view.visible(records).map { it.seq })
        view.hidePings = false
        assertEquals(4, view.visible(records).size)
        view.filter = "read"
        assertEquals(listOf(1L, 2L), view.visible(records).map { it.seq })
        view.filter = "event"
        assertEquals(listOf(3L), view.visible(records).map { it.seq })
    }
}
