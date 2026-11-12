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

// The waits are judged by their 90th percentile: held back, every small answer waits (half the preparation on
// average), while a pause of a busy runner (155 ms seen on the Windows one, 34 to 80 ms otherwise) delays a few.
private const val JUDGED_PERCENT = 90
private const val PERCENT = 100

// Held back, the small answers wait in every round; a runner stalled by what else it runs (a 90th percentile of
// 500 ms once on the Windows one, as a language server started, for 2 to 8 ms in the ten runs before) stalls one.
private const val MAX_ROUNDS = 3

/** A large answer (a file of 30 MB) does not hold back the small answers sent while it is being prepared. */
class LargeAnswerTest {
    private val data = Files.createTempDirectory("large-answer-data")
    private val project = Files.createTempDirectory("large-answer-project")
    private val server = EditorServer(LocalServices(data.toFile())).start()
    private val socket = Socket("127.0.0.1", server.port).apply { soTimeout = WAIT_LARGE_MS.toInt() }
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val codec = Protocol.newCodec()
    private var nextSmallId = LARGE_ID + 1

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

        val rounds = ArrayList<Round>()
        while (rounds.size < MAX_ROUNDS && rounds.none { it.judgedMs < HELD_BACK_BUDGET_MS }) {
            val round = measureRound(large, small)
            println(
                "PERF small answer while a 30 MB one is prepared: worst ${round.worstMs}ms, " +
                    "${JUDGED_PERCENT}th percentile ${round.judgedMs}ms over ${round.answers} answers",
            )
            assertTrue(round.answers > 0, "no small request was sent while the large answer was prepared")
            rounds += round
        }
        val best = rounds.minOf { it.judgedMs }
        assertTrue(best < HELD_BACK_BUDGET_MS, "small answers waited $best ms for the large one, at best")
    }

    // Asks for the large file, then for the small one every few milliseconds until the large answer arrived.
    private fun measureRound(large: JsonElement, small: JsonElement): Round {
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
        while (!arrived.containsKey(LARGE_ID)) {
            askSmall(small, sent)
            Thread.sleep(SMALL_EVERY_MS)
        }
        // One more answer to wake the reader up once the others arrived.
        stopped.set(true)
        askSmall(small, sent)
        reader.join(WAIT_LARGE_MS)

        val largeAt = arrived.getValue(LARGE_ID)
        val heldBack = sent.filterValues { it < largeAt }.keys
        val waits = heldBack.mapNotNull { id -> arrived[id]?.let { it - sent.getValue(id) } }
        val sorted = waits.sorted()
        val worst = sorted.lastOrNull() ?: 0L
        val judged = sorted.getOrNull(sorted.size * JUDGED_PERCENT / PERCENT) ?: worst
        return Round(TimeUnit.NANOSECONDS.toMillis(worst), TimeUnit.NANOSECONDS.toMillis(judged), waits.size)
    }

    private fun askSmall(small: JsonElement, sent: MutableMap<Long, Long>) {
        val id = nextSmallId
        nextSmallId++
        sent[id] = System.nanoTime()
        send(Frame(FrameKind.Request, id, readParams(small), Method.Read))
    }

    private class Round(val worstMs: Long, val judgedMs: Long, val answers: Int)

    private companion object {
        const val SKIPPED_BODY = 64 * 1024
        const val END_OF_HEADERS = "\r\n\r\n"
    }
}
