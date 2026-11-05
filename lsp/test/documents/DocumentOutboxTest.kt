package org.example.kotlineditor.lsp.documents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspFraming
import org.example.kotlineditor.model.TextChange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MEGABYTE = 1024 * 1024
private const val WAIT_MS = 10_000L

/** A stream that blocks until [gate] opens, as the stdout of a server that writes nothing. */
private class GatedInput(private val gate: CountDownLatch) : InputStream() {
    override fun read(): Int {
        gate.await()
        return -1
    }
}

/** The stdin of a server that reads nothing until [gate] opens; what it reads afterwards is kept. */
private class GatedOutput(private val gate: CountDownLatch) : OutputStream() {
    /** Opens once the outbox is blocked writing its first message. */
    val writing = CountDownLatch(1)
    private val written = ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        writing.countDown()
        gate.await()
        synchronized(written) { written.write(b, off, len) }
    }

    /** The messages read so far: their method and parameters. */
    fun messages(): List<Pair<String, JsonObject>> {
        val bytes = synchronized(written) { written.toByteArray() }
        val input = ByteArrayInputStream(bytes)
        val read = ArrayList<Pair<String, JsonObject>>()
        var body = LspFraming.read(input)
        while (body != null) {
            val message = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val method = message["method"]!!.jsonPrimitive.content
            read += method to message["params"]!!.jsonObject
            body = LspFraming.read(input)
        }
        return read
    }
}

class DocumentOutboxTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = CountDownLatch(1)
    private val output = GatedOutput(gate)
    private val link = LspConnection(GatedInput(gate), output, scope)

    @AfterTest
    fun cleanup() {
        gate.countDown()
        scope.cancel()
    }

    private fun usedMemory(): Long {
        repeat(3) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun document(text: String, path: String = "/project/a.php"): MirroredDocument =
        MirroredDocument(path, "php", "/project").apply { reset("s1", 1, text) }

    private fun MirroredDocument.edit(outbox: DocumentOutbox, vararg changes: TextChange) {
        apply("s1", clientVersion.value.version + 1, changes.toList())
        outbox.didChange(this, changes.toList())
    }

    // The outbox gets stuck on a first message, as on a server that stopped reading: what follows waits behind it.
    private fun blockOnFirstMessage(outbox: DocumentOutbox) {
        outbox.filesChanged(listOf("file:///project/first.php" to 2))
        assertTrue(output.writing.await(WAIT_MS, TimeUnit.MILLISECONDS), "the outbox did not write")
    }

    private fun releaseAndRead(outbox: DocumentOutbox): List<Pair<String, JsonObject>> {
        gate.countDown()
        runBlocking { withTimeout(WAIT_MS) { outbox.flush() } }
        return output.messages()
    }

    private fun JsonObject.version() = this["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int

    private fun JsonObject.contentChanges() =
        this["contentChanges"]!!.jsonArray.map { change ->
            val fields = change.jsonObject
            val range = fields["range"]?.jsonObject
            val start = range?.get("start")?.jsonObject
            val at = start?.let { "${it["line"]!!.jsonPrimitive.int}:${it["character"]!!.jsonPrimitive.int} " }
            at.orEmpty() + fields["text"]!!.jsonPrimitive.content
        }

    @Test
    fun `a server that does not read keeps one text per document, not one per change`() {
        val outbox = DocumentOutbox(link, scope, SyncKind.Full, maxUnreadBytes = Long.MAX_VALUE)
        val document = MirroredDocument("/project/big.php", "php", "/project")
        document.lines += "x".repeat(MEGABYTE)
        val before = usedMemory()
        repeat(300) { outbox.didChange(document, listOf(TextChange(0, 0, 0, 0, "y"))) }
        val retained = usedMemory() - before
        println("PERF outbox of 300 changes of a 1 MB document: ${retained / MEGABYTE} MB retained")
        assertTrue(retained < 32L * MEGABYTE, "${retained / MEGABYTE} MB retained by the outbox")
    }

    @Test
    fun `with full sync, the changes waiting for the server are sent once, with the latest text`() {
        val outbox = DocumentOutbox(link, scope, SyncKind.Full, maxUnreadBytes = Long.MAX_VALUE)
        blockOnFirstMessage(outbox)
        val document = document("abc")
        outbox.didOpen(document)
        document.edit(outbox, TextChange(0, 3, 0, 3, "d"))
        document.edit(outbox, TextChange(0, 4, 0, 4, "\ne"))
        document.edit(outbox, TextChange(1, 1, 1, 1, "f"))

        val messages = releaseAndRead(outbox)
        val methods = messages.map { it.first }
        assertEquals(
            listOf("workspace/didChangeWatchedFiles", "textDocument/didOpen", "textDocument/didChange"),
            methods,
        )
        val opened = messages[1].second["textDocument"]!!.jsonObject
        assertEquals("abc", opened["text"]!!.jsonPrimitive.content)
        assertEquals(document.lspVersion - 3, opened["version"]!!.jsonPrimitive.int)
        val changed = messages[2].second
        assertEquals(document.lspVersion, changed.version())
        assertEquals(listOf("abcd\nef"), changed.contentChanges())
    }

    @Test
    fun `with incremental sync, the changes waiting are merged in order`() {
        val outbox = DocumentOutbox(link, scope, SyncKind.Incremental, maxUnreadBytes = Long.MAX_VALUE)
        blockOnFirstMessage(outbox)
        val document = document("abc")
        outbox.didOpen(document)
        document.edit(outbox, TextChange(0, 3, 0, 3, "d"))
        document.edit(outbox, TextChange(0, 0, 0, 1, ""), TextChange(0, 3, 0, 3, "!"))

        val messages = releaseAndRead(outbox)
        assertEquals(3, messages.size, "$messages")
        val changed = messages[2].second
        assertEquals(document.lspVersion, changed.version())
        assertEquals(listOf("0:3 d", "0:0 ", "0:3 !"), changed.contentChanges())
        assertEquals("bcd!", document.text())
    }

    @Test
    fun `too many incremental changes waiting are replaced by the whole text`() {
        val outbox =
            DocumentOutbox(link, scope, SyncKind.Incremental, maxUnreadBytes = Long.MAX_VALUE, maxChangeBytes = 1_000)
        blockOnFirstMessage(outbox)
        val document = document("")
        outbox.didOpen(document)
        repeat(100) { document.edit(outbox, TextChange(0, 0, 0, 0, "x")) }
        document.edit(outbox, TextChange(0, 0, 0, 0, "y"))

        val messages = releaseAndRead(outbox)
        assertEquals(3, messages.size, "$messages")
        val changed = messages[2].second
        assertEquals(document.lspVersion, changed.version())
        assertEquals(listOf("y" + "x".repeat(100)), changed.contentChanges())
    }

    @Test
    fun `changes are not merged across a close, an open or a save of their document`() {
        val outbox = DocumentOutbox(link, scope, SyncKind.Incremental, maxUnreadBytes = Long.MAX_VALUE)
        blockOnFirstMessage(outbox)
        val document = document("a")
        val other = document("z", path = "/project/b.php")
        outbox.didOpen(document)
        document.edit(outbox, TextChange(0, 1, 0, 1, "b"))
        outbox.didSave(document)
        document.edit(outbox, TextChange(0, 2, 0, 2, "c"))
        other.edit(outbox, TextChange(0, 1, 0, 1, "y"))
        outbox.didClose(document)
        document.reset("s1", 10, "new")
        outbox.didOpen(document)
        document.edit(outbox, TextChange(0, 3, 0, 3, "!"))
        other.edit(outbox, TextChange(0, 2, 0, 2, "x"))

        val messages = releaseAndRead(outbox).drop(1)
        val described =
            messages.map { (method, params) ->
                val uri = params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content
                val name = uri.substringAfterLast('/')
                val changes = params["contentChanges"]?.let { params.contentChanges() }.orEmpty()
                "${method.substringAfter('/')} $name $changes"
            }
        val expected =
            listOf(
                "didOpen a.php []",
                "didChange a.php [0:1 b]",
                "didSave a.php []",
                "didChange a.php [0:2 c]",
                // The other document's later change joins its first one: it never waits behind a.php.
                "didChange b.php [0:1 y, 0:2 x]",
                "didClose a.php []",
                "didOpen a.php []",
                "didChange a.php [0:3 !]",
            )
        assertEquals(expected, described)
    }

    @Test
    fun `a flush returns once what was queued before it is written`() {
        val outbox = DocumentOutbox(link, scope, SyncKind.Full, maxUnreadBytes = Long.MAX_VALUE)
        blockOnFirstMessage(outbox)
        val document = document("abc")
        outbox.didOpen(document)
        val flushed = scope.async { outbox.flush() }
        document.edit(outbox, TextChange(0, 0, 0, 0, "x"))
        Thread.sleep(100)
        assertFalse(flushed.isCompleted, "flushed before the server read anything")
        gate.countDown()
        runBlocking { withTimeout(WAIT_MS) { flushed.await() } }
        val methods = output.messages().map { it.first }
        assertTrue("textDocument/didOpen" in methods, "$methods")
    }

    @Test
    fun `a server that leaves too much unread is stuck, its queue dropped once`() {
        val stuck = AtomicInteger()
        val unread = AtomicLong()
        val outbox =
            DocumentOutbox(link, scope, SyncKind.Full, maxUnreadBytes = 10_000) { bytes ->
                stuck.incrementAndGet()
                unread.set(bytes)
            }
        blockOnFirstMessage(outbox)
        val document = document("x".repeat(3_000))
        outbox.didOpen(document)
        val flushed = scope.async { outbox.flush() }
        repeat(10) { document.edit(outbox, TextChange(0, 0, 0, 0, "y")) }

        assertEquals(1, stuck.get())
        assertTrue(unread.get() > 10_000, "${unread.get()} bytes unread")
        // Whoever waited for the queue is not left waiting for a server that will be restarted.
        runBlocking { withTimeout(WAIT_MS) { flushed.await() } }
        runBlocking { withTimeout(WAIT_MS) { outbox.flush() } }
        gate.countDown()
        Thread.sleep(100)
        assertEquals(listOf("workspace/didChangeWatchedFiles"), output.messages().map { it.first })
    }
}
