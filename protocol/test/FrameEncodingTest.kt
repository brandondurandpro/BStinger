package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameEncodingTest {
    // What the encoder did before it wrote bytes: the reference of the bytes on the wire.
    private fun throughString(frame: Frame): ByteArray = JsonRpc.message(frame).toString().encodeToByteArray()

    @Test
    fun `a message is the bytes its JSON text encodes to, whatever its characters`() {
        val texts =
            listOf(
                "",
                "plain",
                "quote \" backslash \\ slash / tab \t newline \n return \r",
                "controls \u0000 \u0001 \u001f \u007f",
                "accents é à ü, greek λ, cjk 漢字, emoji 😀 👩‍💻",
                "lone surrogates \ud83d and \ude00",
                "x".repeat(70_000) + "é",
            )
        for (text in texts) {
            val payload =
                buildJsonObject {
                    put("text", text)
                    put("number", 12.5)
                    put("big", Long.MAX_VALUE)
                    put("flag", true)
                    put("nothing", JsonNull)
                    put("list", JsonArray(listOf(JsonPrimitive(text), JsonPrimitive(-1))))
                }
            val frame = Frame(FrameKind.Response, 7, payload)
            assertContentEquals(throughString(frame), Protocol.encode(frame), text.take(40))
            // A lone surrogate has no UTF-8 encoding: it is lost on the wire, as it was.
            val survivesUtf8 = text.encodeToByteArray().decodeToString() == text
            if (survivesUtf8) assertEquals(payload, Protocol.decode(Protocol.encode(frame)).payload)
        }
    }

    @Test
    fun `bytes written in pieces come back in order, at every size around a chunk`() {
        for (size in listOf(0, 1, 1_023, 1_024, 1_025, 3_071, 3_072, 3_073, 5_000_000)) {
            val expected = ByteArray(size) { (it * 31).toByte() }
            val bytes = EncodedBytes()
            var at = 0
            var piece = 1
            while (at < size) {
                val count = minOf(piece, size - at)
                if (count == 1) bytes.write(expected[at].toInt()) else bytes.write(expected, at, count)
                at += count
                piece = piece * 3 + 1
            }
            assertContentEquals(expected, bytes.toByteArray(), "$size bytes")
        }
    }

    /** A large answer, as a diff of a large file is: thousands of lines of code, some not Latin-1. */
    @Test
    fun `a large answer is encoded without being held several times`() {
        val lines =
            buildJsonArray {
                repeat(LINES) { add(JsonPrimitive("    val value$it = compute(first, \"texte numéro $it\") // →")) }
            }
        val frame = Frame(FrameKind.Response, 1, lines)
        val size = throughString(frame).size
        val before = allocatedBytes()
        val encoded = Protocol.encode(frame)
        val allocated = allocatedBytes() - before
        val times = allocated.toDouble() / size
        println("PERF ${"encoding a large answer".padEnd(50)} ${"%.1f".format(times)} times its ${size / BYTES_PER_MB} MB")
        assertEquals(size, encoded.size)
        // Measured: 2.0 times its size (the chunks, then the array); 31 times through a String.
        assertTrue(times <= MAX_TIMES_ITS_SIZE, "encoding allocated $times times the size of the message")
    }

    private fun allocatedBytes(): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return threads.currentThreadAllocatedBytes
    }

    private companion object {
        const val LINES = 300_000
        const val BYTES_PER_MB = 1_048_576
        const val MAX_TIMES_ITS_SIZE = 4.0
    }
}
