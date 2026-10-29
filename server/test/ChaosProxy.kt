package org.example.kotlineditor.server

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

private const val BUFFER_SIZE = 16 * 1024

/**
 * TCP proxy that injects network failures between a client and the server: abrupt disconnections, answers lost
 * in flight, and "black holes" (the link stays open but nothing goes through, like a dead Wi-Fi or NAT).
 */
class ChaosProxy(private val targetPort: Int) : Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val links = CopyOnWriteArrayList<Pair<Socket, Socket>>()

    val port: Int get() = server.localPort

    /** While true, bytes are swallowed in both directions without closing anything. */
    @Volatile var blackHole = false

    /** When set, the next bytes coming from the server cut the connection instead of being delivered. */
    @Volatile var cutOnNextServerData = false

    @Volatile var connectionsAccepted = 0
        private set

    init {
        thread(isDaemon = true, name = "chaos-accept") {
            var client = runCatching { server.accept() }.getOrNull()
            while (client != null) {
                connectionsAccepted++
                connect(client)
                client = runCatching { server.accept() }.getOrNull()
            }
        }
    }

    private fun connect(client: Socket) {
        val upstream = runCatching { Socket("127.0.0.1", targetPort) }.getOrNull()
        if (upstream == null) {
            client.close() // server down: refuse like the real network would
        } else {
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            val link = client to upstream
            links += link
            pump(client.getInputStream(), upstream.getOutputStream(), link, fromServer = false)
            pump(upstream.getInputStream(), client.getOutputStream(), link, fromServer = true)
        }
    }

    private fun pump(input: InputStream, output: OutputStream, link: Pair<Socket, Socket>, fromServer: Boolean) {
        thread(isDaemon = true, name = "chaos-pump") {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                var open = true
                while (open) {
                    val n = input.read(buffer)
                    val cut = fromServer && cutOnNextServerData
                    if (cut) cutOnNextServerData = false
                    open = n >= 0 && !cut
                    if (open && !blackHole) {
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
            } catch (ignored: IOException) {
                // a socket of the link was closed: the link is over
            } finally {
                close(link)
            }
        }
    }

    private fun close(link: Pair<Socket, Socket>) {
        links.remove(link)
        runCatching { link.first.close() }
        runCatching { link.second.close() }
    }

    /** Cuts every connection, as a network failure would. */
    fun cutAll() = links.forEach(::close)

    override fun close() {
        server.close()
        cutAll()
    }
}
