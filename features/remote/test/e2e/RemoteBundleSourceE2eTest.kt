package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.example.kotlineditor.remote.RemoteBundleDownload
import org.example.kotlineditor.remote.RemoteTarget
import org.example.kotlineditor.state.RemoteBundleSource
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TreeTags
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server of a remote host taken from the GitHub release of the IDE's version (Settings > Remote Development):
 * a fake release served on loopback stands for GitHub, the SSH host is the real one of [RemoteTestEnvironment].
 */
@OptIn(ExperimentalTestApi::class)
class RemoteBundleSourceE2eTest {
    private val project = SampleProject.standard()
    private val version = "9.9.9"
    private val previousProperties = HashMap<String, String?>()
    private var server: HttpServer? = null
    private val requested = ArrayList<String>()

    @BeforeTest
    fun prepare() {
        RemoteTestEnvironment.prepare()
        val names = listOf(RemoteBundleDownload.VERSION_PROPERTY, RemoteBundleDownload.RELEASES_PROPERTY, "user.home")
        for (name in names) {
            previousProperties[name] = System.getProperty(name)
        }
    }

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        for ((name, value) in previousProperties) {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
        project.delete()
    }

    // A release of loopback: the files it serves by name, 404 for the others.
    private fun serveRelease(files: Map<String, ByteArray>): String {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange -> answer(exchange, files) }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/release"
    }

    private fun answer(exchange: HttpExchange, files: Map<String, ByteArray>) {
        exchange.use {
            val name = exchange.requestURI.path.substringAfterLast('/')
            requested += name
            val body = files[name]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
    }

    // The test host is this machine: its `uname` as the installer reads it.
    private fun hostTarget(): RemoteTarget {
        val process = ProcessBuilder("uname", "-sm").start()
        val output = process.inputStream.bufferedReader().readText()
        val uname = output.trim()
        val parts = uname.split(' ')
        val os = checkNotNull(RemoteTarget.osOf(parts[0]))
        val arch = checkNotNull(RemoteTarget.archOf(parts[1]))
        return RemoteTarget(os, arch)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(checksum: (ByteArray) -> String = ::sha256): Map<String, ByteArray> {
        val target = hostTarget()
        val asset = RemoteBundleDownload.assetName(version, target)
        val bundle = Files.readAllBytes(RemoteTestEnvironment.bundle)
        val sums = "${checksum(bundle)}  $asset\n"
        return mapOf(asset to bundle, "SHA256SUMS.txt" to sums.toByteArray())
    }

    // The IDE finds no local bundle: only the download can provide the server.
    private fun useReleaseOnly(releases: String) {
        System.clearProperty("kotlin.editor.remote.bundle")
        System.setProperty("user.home", Files.createTempDirectory("remote-source-home").toString())
        System.setProperty(RemoteBundleDownload.VERSION_PROPERTY, version)
        System.setProperty(RemoteBundleDownload.RELEASES_PROPERTY, releases)
    }

    @Test
    fun `the server is downloaded from the release, verified, kept, and a project opens on it`() =
        remoteUiTest {
            useReleaseOnly(serveRelease(release()))
            val app = launchApp()
            runOnUiThread { app.state.settings.remoteBundleSource = RemoteBundleSource.GitHub }
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(TreeTags.treeRow("src"), timeout = 120_000)
            assertTrue(app.state.session.isRemote, "the IDE should show the remote server")
            onNodeWithTag(AppTags.STATUS_REMOTE).assertTextContains("Remote: tester@127.0.0.1", substring = true)
            val bundleDownloads = requested.count { it.endsWith(".tar.gz") }
            assertEquals(1, bundleDownloads, "the bundle is downloaded once: $requested")
        }

    @Test
    fun `a bundle already downloaded is not downloaded again`() =
        remoteUiTest {
            useReleaseOnly(serveRelease(release()))
            val target = hostTarget()
            val first = RemoteBundleDownload.fetch(target)
            val second = RemoteBundleDownload.fetch(target)
            assertEquals(first, second)
            assertEquals(1, requested.count { it.endsWith(".tar.gz") }, "downloaded once: $requested")
        }

    @Test
    fun `a release without a server bundle is reported in the dialog, with what to do`() =
        remoteUiTest {
            useReleaseOnly(serveRelease(emptyMap()))
            val app = launchApp()
            runOnUiThread { app.state.settings.remoteBundleSource = RemoteBundleSource.GitHub }
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(RemoteTags.REMOTE_ERROR, timeout = 60_000)
            onNodeWithTag(RemoteTags.REMOTE_ERROR).assertTextContains("Remote Development", substring = true)
            assertFalse(app.state.session.isRemote)
        }

    @Test
    fun `a bundle whose checksum differs is refused and not kept`() =
        remoteUiTest {
            useReleaseOnly(serveRelease(release { "0".repeat(64) }))
            val app = launchApp()
            runOnUiThread { app.state.settings.remoteBundleSource = RemoteBundleSource.GitHub }
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(RemoteTags.REMOTE_ERROR, timeout = 60_000)
            onNodeWithTag(RemoteTags.REMOTE_ERROR).assertTextContains("corrupted", substring = true)
            assertFalse(app.state.session.isRemote)
            val home = Paths.get(System.getProperty("user.home"))
            val kept =
                Files.walk(home).use { files ->
                    files.filter { it.toString().endsWith(".tar.gz") }.count()
                }
            assertEquals(0L, kept, "a refused bundle must not stay on disk")
        }
}
