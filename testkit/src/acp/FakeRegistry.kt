package org.example.kotlineditor.testkit.acp

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An ACP registry on loopback, for tests: the agent `fake-agent`, a zip of a script that starts the [FakeAgent]
 * (`fake-agent` on Linux and macOS, `fake-agent.cmd` on Windows) published for [platform] with its SHA-256; the
 * agent `unsigned-agent`, the same without a checksum; `npx-agent`, an npm package; and `elsewhere-agent`, built
 * for another platform only; then [extraAgents] more npm packages, for a large registry. [downloads] counts the
 * downloads of the archive.
 */
class FakeRegistry(private val platform: String, private val extraAgents: Int = 0) : AutoCloseable {
    private val windows = platform.startsWith("windows")
    private val program = if (windows) "fake-agent.cmd" else "fake-agent"
    private val archive = zipOf(program, launcher())
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** How many times the archive was downloaded. */
    val downloads = AtomicInteger()

    /** While true, the registry and its downloads answer 503, as a CDN that is down. */
    @Volatile
    var down = false

    /** Address of the registry document. */
    val url: String

    init {
        server.createContext("/registry.json") { exchange ->
            if (down) return@createContext unavailable(exchange)
            val body = registry().toByteArray()
            exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/fake-agent.zip") { exchange ->
            if (down) return@createContext unavailable(exchange)
            downloads.incrementAndGet()
            exchange.sendResponseHeaders(HTTP_OK, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/registry.json"
    }

    override fun close() = server.stop(0)

    private fun unavailable(exchange: com.sun.net.httpserver.HttpExchange) {
        exchange.sendResponseHeaders(HTTP_UNAVAILABLE, -1)
        exchange.close()
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun registry(): String {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive))
        val signed = """{"archive":"${base()}/fake-agent.zip","cmd":"./$program","sha256":"$sha"}"""
        val unsigned = """{"archive":"${base()}/fake-agent.zip","cmd":"./$program"}"""
        val extra =
            (1..extraAgents).joinToString("") { index ->
                """,{"id":"extra-$index","name":"Extra Agent $index","version":"1.0.$index","description":"Extra",""" +
                    """"distribution":{"npx":{"package":"@example/extra-$index"}}}"""
            }
        val elsewhere = if (platform == "linux-x86_64") "darwin-aarch64" else "linux-x86_64"
        return """
            {"version":"1.0.0","extensions":[],"agents":[
              {"id":"fake-agent","name":"Fake Agent","version":"1.0.0","description":"The agent of the tests",
               "distribution":{"binary":{"$platform":$signed}}},
              {"id":"unsigned-agent","name":"Unsigned Agent","version":"1.0.0","description":"No checksum",
               "distribution":{"binary":{"$platform":$unsigned}}},
              {"id":"npx-agent","name":"Npx Agent","version":"2.0.0","description":"An npm package",
               "distribution":{"npx":{"package":"@example/npx-agent@2.0.0","args":["--acp"],"env":{"MODE":"acp"}}}},
              {"id":"elsewhere-agent","name":"Elsewhere Agent","version":"1.0.0","description":"Another platform",
               "distribution":{"binary":{"$elsewhere":$signed}}}$extra
            ]}
            """.trimIndent()
    }

    // A script that starts the fake agent with the classpath of the tests, its arguments passed on.
    private fun launcher(): String {
        val command = FakeAgentCommand.command()
        val quoted = command.joinToString(" ") { "\"$it\"" }
        return if (windows) "@echo off\r\n$quoted %*\r\n" else "#!/bin/sh\nexec $quoted \"$@\"\n"
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAVAILABLE = 503

        fun zipOf(name: String, text: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            return bytes.toByteArray()
        }
    }
}
