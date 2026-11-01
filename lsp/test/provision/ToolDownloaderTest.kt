package org.example.kotlineditor.lsp.provision

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Downloads of pinned tools from a local HTTP server: checksums, failures, reuse and npm tarballs. */
class ToolDownloaderTest {
    private val tools = Files.createTempDirectory("tools")
    private val requests = AtomicInteger()
    private val files = mutableMapOf<String, ByteArray>()
    private var truncate = false
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                requests.incrementAndGet()
                val body = files[exchange.requestURI.path]
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(if (truncate) body.copyOf(body.size / 2) else body)
                }
                exchange.close()
            }
            start()
        }
    private val downloader = ToolDownloader(tools)

    @AfterTest
    fun cleanup() {
        server.stop(0)
        tools.toFile().deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha512Base64(bytes: ByteArray) =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))

    private fun phar(
        content: ByteArray = "<?php echo 'server';".toByteArray(),
        digest: String = sha256(content),
    ): PinnedTool {
        files["/phpactor.phar"] = content
        return PinnedTool("phpactor", "1.0", url("/phpactor.phar"), Digest(Digest.SHA_256, digest), "phpactor.phar")
    }

    @Test
    fun `a verified file is installed in its version directory`() {
        val installed = downloader.obtain(phar())
        assertEquals(tools.resolve("phpactor/1.0/phpactor.phar"), installed)
        assertEquals("<?php echo 'server';", Files.readString(installed))
    }

    @Test
    fun `a copy verified earlier is reused without downloading again`() {
        val tool = phar()
        downloader.obtain(tool)
        downloader.obtain(tool)
        ToolDownloader(tools).obtain(tool)
        assertEquals(1, requests.get())
    }

    @Test
    fun `a file that does not match the pin is never installed`() {
        val tool = phar(digest = sha256("something else".toByteArray()))
        val failure = assertFailsWith<IOException> { downloader.obtain(tool) }
        assertContains(failure.message.orEmpty(), "Checksum mismatch")
        assertNoFileLeft()
    }

    @Test
    fun `a copy modified on disk is downloaded again`() {
        val tool = phar()
        val installed = downloader.obtain(tool)
        Files.writeString(installed, "tampered")
        downloader.obtain(tool)
        assertEquals("<?php echo 'server';", Files.readString(installed))
        assertEquals(2, requests.get())
    }

    @Test
    fun `a missing file or a connection cut mid-body leaves nothing installed`() {
        val digest = Digest(Digest.SHA_256, sha256(ByteArray(1)))
        val missing = PinnedTool("phpactor", "1.0", url("/missing"), digest, "phpactor.phar")
        assertContains(assertFailsWith<IOException> { downloader.obtain(missing) }.message.orEmpty(), "HTTP 404")
        assertNoFileLeft()
        val tool = phar()
        truncate = true
        assertFailsWith<IOException> { downloader.obtain(tool) }
        assertNoFileLeft()
    }

    @Test
    fun `concurrent requests for the same tool download it once`() {
        val tool = phar(ByteArray(4 * 1024 * 1024) { it.toByte() })
        val start = CountDownLatch(1)
        val threads =
            List(8) {
                Thread {
                    start.await()
                    downloader.obtain(tool)
                }.apply { start() }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, requests.get())
    }

    @Test
    fun `an npm tarball is verified with its SHA-512 integrity and extracted`() {
        val tgz = gzip(tar("package/package.json" to "{}", "package/lib/intelephense.js" to "console.log(1)"))
        files["/intelephense.tgz"] = tgz
        val tool =
            PinnedTool(
                "intelephense",
                "1.18.5",
                url("/intelephense.tgz"),
                Digest(Digest.SHA_512, sha512Base64(tgz)),
                "intelephense.tgz",
                Unpack.NpmTarball,
            )
        val installed = downloader.obtain(tool)
        assertEquals(tools.resolve("intelephense/1.18.5/package"), installed)
        assertEquals("console.log(1)", Files.readString(installed.resolve("lib/intelephense.js")))
        downloader.obtain(tool)
        assertEquals(1, requests.get())
        assertFalse(Files.exists(tools.resolve("intelephense/1.18.5/intelephense.tgz")))
    }

    @Test
    fun `a tarball entry escaping the target directory is refused`() {
        val tgz = gzip(tar("package/../../evil.txt" to "x"))
        files["/evil.tgz"] = tgz
        val digest = Digest(Digest.SHA_512, sha512Base64(tgz))
        val tool = PinnedTool("evil", "1", url("/evil.tgz"), digest, "evil.tgz", Unpack.NpmTarball)
        assertContains(assertFailsWith<IOException> { downloader.obtain(tool) }.message.orEmpty(), "outside")
        assertFalse(Files.exists(tools.resolve("evil.txt")))
        assertFalse(Files.exists(tools.resolve("evil/1/package")))
    }

    @Test
    fun `a pin with an unreadable checksum is rejected when it is written`() {
        assertFailsWith<IllegalArgumentException> { Digest(Digest.SHA_256, "not a checksum") }
        assertFailsWith<IllegalArgumentException> { Digest("MD5", sha256(ByteArray(1))) }
    }

    private fun assertNoFileLeft() {
        val left = Files.walk(tools).use { paths -> paths.filter(Files::isRegularFile).toList() }
        assertTrue(left.isEmpty(), "left behind: $left")
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}

/** A ustar archive of regular files, written by hand: no tar program is needed. */
internal fun tar(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((name, text) in entries) {
        val content = text.toByteArray()
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000644\u0000".toByteArray().copyInto(header, 100)
        "%011o\u0000".format(content.size).toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        header[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray().copyInto(header, 257)
        "        ".toByteArray().copyInto(header, 148)
        val checksum = header.sumOf { it.toInt() and 0xff }
        "%06o\u0000 ".format(checksum).toByteArray().copyInto(header, 148)
        out.write(header)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}
