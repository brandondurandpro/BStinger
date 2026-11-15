package org.example.kotlineditor.unit

import org.example.kotlineditor.e2e.FakeReleaseServer
import org.example.kotlineditor.remote.DownloadException
import org.example.kotlineditor.remote.DownloadRequest
import org.example.kotlineditor.remote.VerifiedDownload
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The download that keeps only verified files: against a loopback stand-in of the GitHub downloads, never the real
 * network. Used by the server bundle of Remote Development and by the update of the IDE.
 */
class VerifiedDownloadTest {
    private val server = FakeReleaseServer()
    private val work: File = Files.createTempDirectory("verified-download").toFile()
    private val asset = File(work, "asset.bin").apply { writeBytes(ByteArray(SIZE) { (it % BYTE_MODULO).toByte() }) }
    private val target = File(work, "kept")

    @AfterTest
    fun cleanup() {
        server.close()
        work.deleteRecursively()
    }

    private fun request(name: String = asset.name) =
        DownloadRequest("${server.base}/v1", name, target, missing = { "nothing at $it" })

    private fun leftovers(): List<String> = target.list()?.filter { it != asset.name }.orEmpty()

    @Test
    fun `the file is downloaded, verified, kept, and not downloaded again`() {
        server.publish("v1", asset)
        val kept = VerifiedDownload().fetch(request())
        assertEquals(File(target, asset.name), kept)
        assertTrue(kept.readBytes().contentEquals(asset.readBytes()))
        assertEquals(emptyList(), leftovers())
        VerifiedDownload().fetch(request())
        assertEquals(1, server.requests.count { it == "v1/${asset.name}" }, "downloaded once: ${server.requests}")
    }

    @Test
    fun `the progress goes up to the size of the file`() {
        server.publish("v1", asset)
        val reports = ArrayList<Pair<Long, Long>>()
        VerifiedDownload().fetch(request()) { done, total -> reports.add(done to total) }
        assertEquals(SIZE.toLong() to SIZE.toLong(), reports.lastOrNull())
        assertTrue(reports.zipWithNext().all { (before, after) -> before.first <= after.first })
    }

    @Test
    fun `a wrong checksum is refused and nothing is kept`() {
        server.publish("v1", asset, checksum = "0".repeat(HASH_LENGTH))
        val failure = assertFailsWith<DownloadException> { VerifiedDownload().fetch(request()) }
        assertTrue("corrupted" in failure.message.orEmpty(), failure.message)
        assertFalse(File(target, asset.name).exists())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a release whose sums do not list the file, or that does not exist, is reported`() {
        server.publish("v1", asset)
        val unlisted = assertFailsWith<DownloadException> { VerifiedDownload().fetch(request("other.bin")) }
        assertTrue("no checksum for other.bin" in unlisted.message.orEmpty(), unlisted.message)
        val missing =
            assertFailsWith<DownloadException> {
                VerifiedDownload().fetch(
                    DownloadRequest("${server.base}/v2", "x", target, missing = { "nothing at $it" }),
                )
            }
        assertEquals("nothing at ${server.base}/v2/SHA256SUMS.txt", missing.message)
    }

    @Test
    fun `an error status is reported with its code`() {
        server.publish("v1", asset)
        server.assetStatus = SERVER_ERROR
        val failure = assertFailsWith<DownloadException> { VerifiedDownload().fetch(request()) }
        assertTrue("HTTP $SERVER_ERROR" in failure.message.orEmpty(), failure.message)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a refused connection is reported`() {
        val dead = FakeReleaseServer()
        val base = dead.base
        dead.close()
        val failure =
            assertFailsWith<DownloadException> {
                VerifiedDownload().fetch(DownloadRequest("$base/v1", asset.name, target))
            }
        assertTrue("Cannot download" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a body that stops arriving is cut after the stall delay, and its partial file is deleted`() {
        server.publish("v1", asset)
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        val failure =
            assertFailsWith<DownloadException> {
                VerifiedDownload(
                    stallMillis = STALL_MILLIS,
                ).fetch(request())
            }
        assertTrue("nothing received" in failure.message.orEmpty(), failure.message)
        assertEquals(0L, gate.reached.count, "the server held the body")
        assertFalse(Thread.currentThread().isInterrupted, "the watchdog's interrupt is cleared")
        assertEquals(emptyList(), leftovers())
        gate.release()
    }

    @Test
    fun `checksums that stop arriving are cut too`() {
        server.publish("v1", asset)
        val gate = server.holdNextSums()
        val failure =
            assertFailsWith<DownloadException> {
                VerifiedDownload(
                    stallMillis = STALL_MILLIS,
                ).fetch(request())
            }
        assertTrue("nothing received" in failure.message.orEmpty(), failure.message)
        assertFalse(Thread.currentThread().isInterrupted)
        gate.release()
    }

    @Test
    fun `a slow body that keeps arriving is never cut, however long it takes`() {
        server.publish("v1", asset)
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        val releaser =
            Thread {
                gate.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)
                gate.release()
            }
        releaser.start()
        val kept = VerifiedDownload(stallMillis = LONG_STALL_MILLIS).fetch(request())
        assertEquals(SIZE.toLong(), kept.length())
        releaser.join()
    }

    @Test
    fun `interrupting the thread stops the transfer and deletes the partial file`() {
        server.publish("v1", asset)
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread {
                try {
                    VerifiedDownload(stallMillis = LONG_STALL_MILLIS).fetch(request())
                } catch (e: IOException) {
                    failure.set(e)
                }
            }
        thread.start()
        assertTrue(gate.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(thread.isAlive, "the transfer did not stop")
        assertTrue(failure.get() != null, "an interrupted download ends with an error")
        assertEquals(emptyList(), leftovers())
        assertFalse(File(target, asset.name).exists())
        gate.release()
    }

    @Test
    fun `the checksum of a name is found in the sums, whatever the marker and the case`() {
        val sums =
            """
            ABCDEF  one.bin
            123456 *two.bin
            not a line
            """.trimIndent()
        assertEquals("abcdef", VerifiedDownload.checksumOf(sums, "one.bin"))
        assertEquals("123456", VerifiedDownload.checksumOf(sums, "two.bin"))
        assertNull(VerifiedDownload.checksumOf(sums, "three.bin"))
    }

    private companion object {
        const val SIZE = 200_000
        const val BYTE_MODULO = 251
        const val HASH_LENGTH = 64
        const val SERVER_ERROR = 500
        const val HOLD_AFTER = 20_000L
        const val STALL_MILLIS = 400L
        const val LONG_STALL_MILLIS = 30_000L
        const val WAIT_SECONDS = 30L
    }
}
