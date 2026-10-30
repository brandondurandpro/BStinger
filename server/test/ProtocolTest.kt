package org.example.kotlineditor.server

import org.example.kotlineditor.compression.BodyCodec
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.WireReader
import org.example.kotlineditor.rpc.WireWriter
import org.example.kotlineditor.rpc.blameLines
import org.example.kotlineditor.rpc.content
import org.example.kotlineditor.rpc.diffLines
import org.example.kotlineditor.rpc.entries
import org.example.kotlineditor.rpc.gitStatus
import org.example.kotlineditor.rpc.session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `primitives and strings round trip, including unicode and null`() {
        val bytes =
            WireWriter()
                .byte(-3)
                .bool(true)
                .short(65_000)
                .int(Int.MIN_VALUE)
                .long(Long.MAX_VALUE)
                .string("héllo ✓ 日本")
                .string(null)
                .string("")
                .toByteArray()
        val r = WireReader(bytes)
        assertEquals(-3, r.byte())
        assertTrue(r.bool())
        assertEquals(65_000, r.short())
        assertEquals(Int.MIN_VALUE, r.int())
        assertEquals(Long.MAX_VALUE, r.long())
        assertEquals("héllo ✓ 日本", r.string())
        assertEquals(null, r.stringOrNull())
        assertEquals("", r.string())
    }

    @Test
    fun `model objects round trip`() {
        val entries = listOf(FileEntry("/a/b.kt", "b.kt", false), FileEntry("/a/dir", "dir", true))
        val content = FileContent.Text(listOf("x", "", "y"), "\r\n")
        val status = mapOf("a.kt" to GitFileStatus.Modified, "b" to GitFileStatus.Untracked)
        val session = ProjectSession(listOf(SessionFile("/a/b.kt", 1, 2)), null)
        val w = WireWriter()
        w.entries(entries)
        w.content(content)
        w.content(FileContent.TooLarge(42))
        w.gitStatus(status)
        w.session(session)
        val r = WireReader(w.toByteArray())
        assertEquals(entries, r.entries())
        val text = r.content() as FileContent.Text
        assertEquals(content.lines, text.lines)
        assertEquals("\r\n", text.lineSeparator)
        assertEquals(FileContent.TooLarge(42), r.content())
        assertEquals(status, r.gitStatus())
        assertEquals(session, r.session())
    }

    @Test
    fun `diff lines round trip`() {
        val lines =
            listOf(
                DiffLine(DiffKind.Hunk, -1, -1, "@@ -1,2 +1,2 @@"),
                DiffLine(DiffKind.Context, 1, 1, "a"),
                DiffLine(DiffKind.Removed, 2, -1, "b"),
                DiffLine(DiffKind.Added, -1, 2, "c"),
            )
        val w = WireWriter().diffLines(lines)
        assertEquals(lines, WireReader(w.toByteArray()).diffLines())
    }

    @Test
    fun `blame lines round trip`() {
        val lines = listOf(BlameLine(0, "abc12345", "Ana", "2024-05-01"), BlameLine(1, "def67890", "Bob", "2025-01-02"))
        val w = WireWriter().blameLines(lines)
        assertEquals(lines, WireReader(w.toByteArray()).blameLines())
    }

    @Test
    fun `large frames are compressed transparently`() {
        val body = WireWriter().string("abc".repeat(100_000)).toByteArray()
        val buffer = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(buffer), Frame(FrameKind.Response, 7, body), Protocol.newCodec())
        assertTrue(buffer.size() < body.size / 10, "compressed to ${buffer.size()} bytes")
        val frame = Protocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())), Protocol.newCodec())
        assertEquals(FrameKind.Response, frame.kind)
        assertEquals(7, frame.id)
        assertContentEquals(body, frame.body)
    }

    @Test
    fun `code frames round trip`() {
        val writer = WireWriter()
        writer.content(FileContent.Text((1..4_000).map { "    val value$it = compute($it)" }, "\n"))
        val body = writer.toByteArray()
        val buffer = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(buffer), Frame(FrameKind.Response, 8, body, code = true), Protocol.newCodec())
        assertTrue(buffer.size() < body.size / 2, "compressed to ${buffer.size()} bytes")
        val frame = Protocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())), Protocol.newCodec())
        assertContentEquals(body, frame.body)
    }

    @Test
    fun `the code hint reaches the codec`() {
        val codec = RecordingCodec()
        Protocol.write(
            DataOutputStream(ByteArrayOutputStream()),
            Frame(FrameKind.Request, 1, ByteArray(200), code = true),
            codec,
        )
        assertEquals(true, codec.lastCode)
    }

    @Test
    fun `corrupted frames are rejected`() {
        val bad = WireWriter().int(-5).toByteArray()
        assertFailsWith<IOException> {
            Protocol.read(DataInputStream(ByteArrayInputStream(bad)), Protocol.newCodec())
        }
        val truncated = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(truncated), Frame(FrameKind.Request, 1, ByteArray(100)), Protocol.newCodec())
        val full = truncated.toByteArray()
        val cut = full.copyOf(HEADER + (full.size - HEADER) / 2)
        assertIs<IOException>(
            runCatching {
                Protocol.read(DataInputStream(ByteArrayInputStream(cut)), Protocol.newCodec())
            }.exceptionOrNull(),
        )
    }

    private companion object {
        /** Frame header: length, kind, flags and id. */
        const val HEADER = 10
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
}
