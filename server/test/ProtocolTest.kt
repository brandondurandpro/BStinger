package org.example.kotlineditor.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.compression.BodyCodec
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.TerminalAttributes
import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.blameJson
import org.example.kotlineditor.rpc.blameLines
import org.example.kotlineditor.rpc.content
import org.example.kotlineditor.rpc.diffJson
import org.example.kotlineditor.rpc.diffLines
import org.example.kotlineditor.rpc.entries
import org.example.kotlineditor.rpc.errorMessage
import org.example.kotlineditor.rpc.gitStatus
import org.example.kotlineditor.rpc.keyEvent
import org.example.kotlineditor.rpc.params
import org.example.kotlineditor.rpc.rpcError
import org.example.kotlineditor.rpc.session
import org.example.kotlineditor.rpc.snapshot
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.terminalInput
import org.example.kotlineditor.rpc.toJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `model objects round trip`() {
        val entries = listOf(FileEntry("/a/b.kt", "b.kt", false), FileEntry("/a/dir", "dir", true))
        val content = FileContent.Text(listOf("x", "", "y", "héllo ✓ 日本 " + QUOTED), "\r\n")
        val status = mapOf("a.kt" to GitFileStatus.Modified, "b" to GitFileStatus.Untracked)
        val session = ProjectSession(listOf(SessionFile("/a/b.kt", 1, 2)), null)
        assertEquals(entries, roundTrip(entries.toJson()).entries())
        val text = roundTrip(content.toJson()).content() as FileContent.Text
        assertEquals(content.lines, text.lines)
        assertEquals("\r\n", text.lineSeparator)
        assertEquals(FileContent.TooLarge(42), roundTrip(FileContent.TooLarge(42).toJson()).content())
        assertEquals(status, roundTrip(status.toJson()).gitStatus())
        assertEquals(session, roundTrip(session.toJson()).session())
        assertNull(roundTrip((null as ProjectSession?).toJson()).session())
    }

    @Test
    fun `diff and blame lines round trip`() {
        val diff =
            listOf(
                DiffLine(DiffKind.Hunk, -1, -1, "@@ -1,2 +1,2 @@"),
                DiffLine(DiffKind.Context, 1, 1, "a"),
                DiffLine(DiffKind.Removed, 2, -1, "b"),
                DiffLine(DiffKind.Added, -1, 2, "c"),
            )
        assertEquals(diff, roundTrip(diff.diffJson()).diffLines())
        val blame = listOf(BlameLine(0, "abc12345", "Ana", "2024-05-01"), BlameLine(1, "def67890", "Bob", "2025-01-02"))
        assertEquals(blame, roundTrip(blame.blameJson()).blameLines())
    }

    @Test
    fun `terminal lines travel as style runs`() {
        val red = 0xFF0000
        val line =
            listOf(
                TerminalCell("$"),
                TerminalCell(" "),
                TerminalCell("l", foreground = red),
                TerminalCell("s", foreground = red),
                TerminalCell("👍", foreground = red), // two chars, one cell
                TerminalCell(" ", background = red),
                TerminalCell("b", attributes = TerminalAttributes.BOLD),
                TerminalCell.BLANK,
                TerminalCell.BLANK,
            )
        val snapshot =
            TerminalSnapshot(
                columns = 10,
                rows = 1,
                cursorColumn = 1,
                cursorRow = 0,
                cursorVisible = true,
                cursorBlinking = false,
                title = "sh",
                totalRows = 1,
                scrollOffset = 0,
                running = false,
                exitCode = 3,
                lines = listOf(line),
            )
        val json = snapshot.toJson()
        assertEquals(
            """[["$ ",{"t":"ls","f":16711680},{"g":"👍","f":16711680},{"t":" ","b":16711680},{"t":"b","a":1}]]""",
            json.getValue("lines").toString(),
        )
        // Blank cells do not travel: the client pads rows with blanks.
        assertEquals(snapshot.copy(lines = listOf(line.dropLast(2))), roundTrip(json).snapshot())
    }

    @Test
    fun `terminal input travels as text when it is UTF-8, as base64 otherwise`() {
        val typed = "ls é\r".encodeToByteArray()
        val text = buildJsonObject { terminalInput(typed) }
        assertEquals("""{"text":"ls é\r"}""", text.toString())
        assertContentEquals(typed, roundTrip(text).params().terminalInput())
        // An é cut in the middle by a split write: not UTF-8.
        val cut = typed.copyOf(4)
        val data = buildJsonObject { terminalInput(cut) }
        assertEquals("""{"data":"bHMgww=="}""", data.toString())
        assertContentEquals(cut, roundTrip(data).params().terminalInput())
    }

    @Test
    fun `key events round trip`() {
        val key = TerminalKeyEvent(TerminalKey.Enter, control = true)
        assertEquals("""{"key":"Enter","text":"","control":true}""", key.toJson().toString())
        assertEquals(key, roundTrip(key.toJson()).keyEvent())
    }

    @Test
    fun `messages are JSON-RPC 2 requests, answers and notifications`() {
        val request =
            Frame(FrameKind.Request, 3, buildJsonObject { put("path", "/a") }, Method.FileAt)
        assertEquals(
            """{"jsonrpc":"2.0","id":3,"method":"fs/fileAt","params":{"path":"/a"}}""",
            request.body.decodeToString(),
        )
        assertEquals(
            """{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"boom"}}""",
            Frame(FrameKind.Error, 3, rpcError(Protocol.SERVER_ERROR, "boom")).body.decodeToString(),
        )
        assertEquals(
            """{"jsonrpc":"2.0","method":"${'$'}/cancelRequest","params":{"id":9}}""",
            Frame(FrameKind.Cancel, 9).body.decodeToString(),
        )
        val event = Frame(FrameKind.Event, 5, buildJsonObject { put("seq", 2) })
        assertEquals(
            """{"jsonrpc":"2.0","method":"stream/event","params":{"stream":5,"seq":2}}""",
            event.body.decodeToString(),
        )

        val decoded = transfer(request)
        assertEquals(FrameKind.Request, decoded.kind)
        assertEquals(Method.FileAt, decoded.method)
        assertEquals("/a", decoded.payload.params().string("path"))
        val stream = transfer(event)
        assertEquals(FrameKind.Event, stream.kind)
        assertEquals(5, stream.id)
        assertEquals("2", stream.payload.params().string("seq"))
        val error = transfer(Frame(FrameKind.Error, 3, rpcError(Protocol.SERVER_ERROR, "boom")))
        assertEquals("boom", error.payload.errorMessage())
        assertEquals(FrameKind.Welcome, transfer(Frame(FrameKind.Welcome, Protocol.HELLO_ID, JsonPrimitive(1))).kind)
        assertEquals(FrameKind.Reject, transfer(Frame(FrameKind.Reject, Protocol.HELLO_ID, rpcError(1, "no"))).kind)
    }

    @Test
    fun `an unknown method is a request without method`() {
        val frame = Protocol.decode("""{"jsonrpc":"2.0","id":4,"method":"nope/nothing"}""".encodeToByteArray())
        assertEquals(FrameKind.Request, frame.kind)
        assertEquals(4, frame.id)
        assertNull(frame.method)
    }

    @Test
    fun `large messages are compressed transparently`() {
        val frame = Frame(FrameKind.Response, 7, JsonPrimitive("abc".repeat(100_000)))
        val buffer = ByteArrayOutputStream()
        Protocol.write(buffer, frame, Protocol.newCodec())
        assertTrue(buffer.size() < frame.body.size / 10, "compressed to ${buffer.size()} bytes")
        assertTrue(buffer.toString(Charsets.US_ASCII).startsWith("Content-Length: "))
        assertTrue(buffer.toString(Charsets.US_ASCII).contains("Content-Encoding: zstd\r\n\r\n"))
        val read = Protocol.read(ByteArrayInputStream(buffer.toByteArray()), Protocol.newCodec())
        assertEquals(FrameKind.Response, read.kind)
        assertEquals(7, read.id)
        assertEquals(frame.payload, read.payload)
    }

    @Test
    fun `code messages round trip`() {
        val text = FileContent.Text((1..4_000).map { "    val value$it = compute($it)" }, "\n")
        val frame = Frame(FrameKind.Response, 8, text.toJson(), code = true)
        val buffer = ByteArrayOutputStream()
        Protocol.write(buffer, frame, Protocol.newCodec())
        assertTrue(buffer.size() < frame.body.size / 2, "compressed to ${buffer.size()} bytes")
        val read = Protocol.read(ByteArrayInputStream(buffer.toByteArray()), Protocol.newCodec())
        assertEquals(text.lines, (read.payload.content() as FileContent.Text).lines)
    }

    @Test
    fun `small messages are plain JSON after the headers`() {
        val frame = Frame(FrameKind.Ping, 0)
        val buffer = ByteArrayOutputStream()
        Protocol.write(buffer, frame, Protocol.newCodec())
        assertEquals(
            "Content-Length: 35" + CRLF + CRLF + """{"jsonrpc":"2.0","method":"${'$'}/ping"}""",
            buffer.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `the code hint reaches the codec`() {
        val codec = RecordingCodec()
        Protocol.write(ByteArrayOutputStream(), Frame(FrameKind.Request, 1, method = Method.Write, code = true), codec)
        assertEquals(true, codec.lastCode)
    }

    @Test
    fun `corrupted messages are rejected`() {
        fun read(text: String) = Protocol.read(ByteArrayInputStream(text.encodeToByteArray()), Protocol.newCodec())
        assertFailsWith<IOException> { read("Content-Length: -5\r\n\r\n") }
        assertFailsWith<IOException> { read("\r\n{}") } // no Content-Length
        assertFailsWith<IOException> { read("Content-Length: 3" + CRLF + CRLF + """{"j""") } // not JSON
        assertFailsWith<IOException> { read("Content-Length: 2\r\n\r\n{}") } // not a JSON-RPC message
        assertFailsWith<IOException> { read("Content-Length: " + "9".repeat(2_000)) } // endless header
        val buffer = ByteArrayOutputStream()
        Protocol.write(buffer, Frame(FrameKind.Response, 1, JsonPrimitive("x".repeat(100))), Protocol.newCodec())
        val full = buffer.toByteArray()
        assertIs<IOException>(
            runCatching {
                Protocol.read(ByteArrayInputStream(full.copyOf(full.size - 10)), Protocol.newCodec())
            }.exceptionOrNull(),
        )
    }

    private fun roundTrip(element: JsonElement) = Json.parseToJsonElement(element.toString())

    private fun transfer(frame: Frame): Frame {
        val buffer = ByteArrayOutputStream()
        Protocol.write(buffer, frame, Protocol.newCodec())
        return Protocol.read(ByteArrayInputStream(buffer.toByteArray()), Protocol.newCodec())
    }

    /** Records the compression hint instead of compressing. */
    private class RecordingCodec : BodyCodec {
        var lastCode: Boolean? = null

        override fun compress(body: ByteArray, code: Boolean): ByteArray? {
            lastCode = code
            return null
        }

        override fun decompress(body: ByteArray): ByteArray = body
    }

    private companion object {
        const val CRLF = "\r\n"
        const val QUOTED = """"quoted""""
    }
}
