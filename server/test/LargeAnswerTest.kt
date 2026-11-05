package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// About 30 MB, under the 32 MiB a file can have to be opened.
private const val LARGE_FILE_LINES = 450_000
private const val LARGE_ID = 1_000L
private const val SMALL_EVERY_MS = 2L
private const val WAIT_LARGE_MS = 30_000L

// Before the fix, a small answer waited for the whole encoding, compression and write of the large one (337 ms on a
// developer machine for 30 MB); now only for the write of the frame already on the socket.
private const val HELD_BACK_BUDGET_MS = 150L

/** A large answer (a file of 30 MB) does not hold back the small answers sent while it is being prepared. */
class LargeAnswerTest {
    private val data = Files.createTempDirectory("large-answer-data")
    private val project = Files.createTempDirectory("large-answer-project")
    private val server = EditorServer(LocalServices(data.toFile())).start()
    private val socket = Socket("127.0.0.1", server.port).apply { soTimeout = WAIT_LARGE_MS.toInt() }
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val codec = Protocol.newCodec()

    @AfterTest
    fun cleanup() {
        socket.close()
        codec.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    private fun send(frame: Frame) =
        synchronized(output) {
            Protocol.write(output, frame, codec)
        }

    // Reads one message; a large body is skipped unread, so that the client side costs nothing.
    private fun readOrSkip(): Pair<Long, Frame?> {
        val headers = ByteArrayOutputStream()
        while (!headers.toString(Charsets.US_ASCII).endsWith(END_OF_HEADERS)) {
            val byte = input.read()
            check(byte >= 0) { "connection closed" }
            headers.write(byte)
        }
        val text = headers.toString(Charsets.US_ASCII)
        val length = text.substringAfter("Content-Length: ").substringBefore("\r\n").toInt()
        if (length > SKIPPED_BODY) {
            input.skipNBytes(length.toLong())
            return LARGE_ID to null
        }
        val replayed = (text + String(input.readNBytes(length), Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
        Protocol.newCodec().use { reader ->
            val frame = Protocol.read(replayed.inputStream(), reader)
            return frame.id to frame
        }
    }

    private fun call(id: Long, method: Method, params: JsonElement): JsonElement {
        send(Frame(FrameKind.Request, id, params, method))
        val (answered, frame) = readOrSkip()
        assertEquals(id, answered)
        return assertNotNull(frame).payload
    }

    private fun entryOf(path: String, id: Long) = call(id, Method.FileAt, buildJsonObject { put("path", path) })

    private fun readParams(entry: JsonElement) = buildJsonObject { put("file", entry) }

    @Test
    fun `small answers are not held back while a large one is prepared`() {
        val random = Random(1)
        val lines = List(LARGE_FILE_LINES) { "val value$it = compute(${random.nextLong()}, \"${random.nextInt()}\")" }
        project.resolve("large.kt").writeText(lines.joinToString("\n"))
        project.resolve("small.kt").writeText("x")
        val params =
            buildJsonObject {
                put("version", Protocol.VERSION)
                put("token", server.token)
            }
        send(Frame(FrameKind.Hello, Protocol.HELLO_ID, params))
        assertEquals(FrameKind.Welcome, readOrSkip().second?.kind)
        val large = entryOf(project.resolve("large.kt").toString(), 1)
        val small = entryOf(project.resolve("small.kt").toString(), 2)
        call(3, Method.Read, readParams(small))

        val sent = ConcurrentHashMap<Long, Long>()
        val arrived = ConcurrentHashMap<Long, Long>()
        val stopped = AtomicBoolean(false)
        val reader =
            thread(isDaemon = true) {
                while (!stopped.get() || !arrived.keys.containsAll(sent.keys)) {
                    val (id, _) = readOrSkip()
                    arrived[id] = System.nanoTime()
                }
            }
        send(Frame(FrameKind.Request, LARGE_ID, readParams(large), Method.Read))
        var id = LARGE_ID + 1
        while (!arrived.containsKey(LARGE_ID)) {
            sent[id] = System.nanoTime()
            send(Frame(FrameKind.Request, id, readParams(small), Method.Read))
            id++
            Thread.sleep(SMALL_EVERY_MS)
        }
        // One more answer to wake the reader up once the others arrived.
        stopped.set(true)
        sent[id] = System.nanoTime()
        send(Frame(FrameKind.Request, id, readParams(small), Method.Read))
        reader.join(WAIT_LARGE_MS)

        val largeAt = arrived.getValue(LARGE_ID)
        val heldBack = sent.filterValues { it < largeAt }.keys
        val waits = heldBack.mapNotNull { small -> arrived[small]?.let { it - sent.getValue(small) } }
        val worstMs = TimeUnit.NANOSECONDS.toMillis(waits.maxOrNull() ?: 0L)
        println("PERF small answer while a 30 MB one is prepared: worst ${worstMs}ms over ${waits.size} answers")
        assertTrue(waits.isNotEmpty(), "no small request was sent while the large answer was prepared")
        assertTrue(worstMs < HELD_BACK_BUDGET_MS, "a small answer waited $worstMs ms for the large one")
    }

    private companion object {
        const val SKIPPED_BODY = 64 * 1024
        const val END_OF_HEADERS = "\r\n\r\n"
    }
}
