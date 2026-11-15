package org.example.kotlineditor.unit

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.state.AppVersion
import org.example.kotlineditor.state.GitHubReleases
import org.example.kotlineditor.state.ReleaseInfo
import org.example.kotlineditor.state.ReleaseSource
import org.example.kotlineditor.state.UpdateNotifier
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private fun version(text: String): AppVersion = AppVersion.parse(text) ?: error("not a version: $text")

    private fun release(tag: String, draft: Boolean = false) = ReleaseInfo(tag, draft)

    @Test
    fun `versions compare number by number, not as text`() {
        assertTrue(version("1.10.0") > version("1.9.0"))
        assertTrue(version("v2") > version("1.99.99"))
        assertEquals(version("1.2"), version("v1.2.0"))
        assertEquals(version("1.2").hashCode(), version("1.2.0").hashCode())
    }

    @Test
    fun `a pre-release is older than its release`() {
        assertTrue(version("1.2.3-rc1") < version("1.2.3"))
        assertTrue(version("1.2.3-rc1") < version("1.2.3-rc2"))
        assertTrue(version("1.2.3-rc2") < version("1.2.3-rc10"))
        assertTrue(version("1.2.3-rc9") > version("1.2.2"))
    }

    @Test
    fun `what is not a version is refused`() {
        listOf("", "latest", "v", "1..2", "1.2.3.4.5", " v1.2", "v1.2 ", "1.2/../x", "99999999999", "1.2-").forEach {
            assertNull(AppVersion.parse(it), "'$it'")
        }
    }

    @Test
    fun `the newest published release is offered when it is newer`() {
        val releases = listOf(release("v0.1.0"), release("v0.3.0"), release("v0.2.0"))
        assertEquals("v0.3.0", UpdateNotifier.newestAfter(version("0.2.0"), releases))
    }

    @Test
    fun `nothing is offered for the same, an older release, or none`() {
        val releases = listOf(release("v0.1.0"), release("v0.2.0"))
        assertNull(UpdateNotifier.newestAfter(version("0.2.0"), releases))
        assertNull(UpdateNotifier.newestAfter(version("1.0.0"), releases))
        assertNull(UpdateNotifier.newestAfter(version("1.0.0"), emptyList()))
    }

    @Test
    fun `drafts, pre-releases of others and strange tags are ignored`() {
        val releases =
            listOf(
                release("v9.0.0", draft = true),
                release("v8.0.0-rc1"),
                release("nightly"),
                release("1.5.0"),
                release("v1.1.0"),
            )
        assertEquals("v1.1.0", UpdateNotifier.newestAfter(version("1.0.0"), releases))
    }

    @Test
    fun `a user of a pre-release is offered the next pre-release`() {
        val releases = listOf(release("v1.0.0-rc2"), release("v1.0.0-rc1"))
        assertEquals("v1.0.0-rc2", UpdateNotifier.newestAfter(version("1.0.0-rc1"), releases))
    }

    @Test
    fun `the releases of the GitHub API are read, a wrong answer fails`() {
        val body =
            """[{"tag_name":"v1.0.0","draft":false,"x":1},{"tag_name":"v2.0.0","draft":true},{"name":"no tag"}]"""
        assertEquals(listOf(release("v1.0.0"), release("v2.0.0", draft = true)), GitHubReleases.parseReleases(body))
        assertFailsWith<Exception> { GitHubReleases.parseReleases("""{"message":"Not Found"}""") }
        assertFailsWith<Exception> { GitHubReleases.parseReleases("<html>") }
    }

    @Test
    fun `the source reads a server and fails on a refusal`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/repos/o/r/releases") { exchange ->
            val body = """[{"tag_name":"v1.2.3","draft":false}]""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/repos/limited/r/releases") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val tags = runBlocking { GitHubReleases("o/r", base).releases() }
            assertEquals(listOf(release("v1.2.3")), tags)
            assertFailsWith<IOException> { runBlocking { GitHubReleases("limited/r", base).releases() } }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a development run does not know its version, so it never checks`() {
        var checked = false
        val source =
            ReleaseSource {
                checked = true
                listOf(release("v9.0.0"))
            }
        val notifier = UpdateNotifier(null, source)
        runBlocking { notifier.run() }
        assertEquals(false, checked)
        assertNull(notifier.available)
    }

    @Test
    fun `a check that fails leaves nothing, the next one finds the release`() {
        var calls = 0
        val flaky =
            ReleaseSource {
                calls++
                if (calls == 1) throw IOException("offline")
                listOf(release("v1.1.0"))
            }
        val notifier = UpdateNotifier("1.0.0", flaky, Duration.ofMillis(10), {})
        runBlocking { withTimeoutOrNull(500) { notifier.run() } }
        assertEquals("v1.1.0", notifier.available)
        assertEquals("https://github.com/brandondurandpro/BStinger/releases/tag/v1.1.0", notifier.releaseUrl)
    }
}
