package org.example.kotlineditor.testkit

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

private const val BUFFER_SIZE = 16 * 1024

/**
 * TCP proxy that injects the network failures a remote IDE meets, between a client and the server:
 * - [cutAll]: the connections break (Wi-Fi switch, VPN reconnect, server process killed);
 * - [goDown] / [comeBack]: the server cannot be reached at all for a while (network down, server restarting);
 * - [blackHole]: the link stays open but nothing goes through (laptop sleep, NAT or Wi-Fi gone silently);
 * - [dropFromServer]: requests reach the server but its answers are lost on the way back (follow it with [cutAll]:
 *   on a live TCP connection nothing is lost, an answer is lost because the connection breaks);
 * - [cutOnNextServerData]: the next bytes from the server cut the connection instead of being delivered;
 * - [latencyMs]: every chunk arrives that much later, like a slow remote link (the chunks in flight do not wait for
 *   each other: latency, not a narrow bandwidth).
 */
class ChaosProxy(private val targetPort: Int) : Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val links = CopyOnWriteArrayList<Pair<Socket, Socket>>()

    /** Port the client connects to instead of the server's. */
    val port: Int get() = server.localPort

    /** While true, bytes are swallowed in both directions without closing anything. */
    @Volatile var blackHole = false

    /** While true, bytes from the server are swallowed; the client's still reach the server. */
    @Volatile var dropFromServer = false

    /** When set, the next bytes coming from the server cut the connection instead of being delivered. */
    @Volatile var cutOnNextServerData = false

    /** Delay added to every chunk forwarded, in each direction. */
    @Volatile var latencyMs = 0L

    /** While true, connections are refused, as when the server's machine cannot be reached. */
    @Volatile var down = false
        private set

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
        val upstream = if (down) null else runCatching { Socket("127.0.0.1", targetPort) }.getOrNull()
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
        val delayed = DelayedOutput(output) { close(link) }
        thread(isDaemon = true, name = "chaos-pump") {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                var open = true
                while (open) {
                    val n = input.read(buffer)
                    val cut = fromServer && cutOnNextServerData
                    if (cut) cutOnNextServerData = false
                    open = n >= 0 && !cut
                    val swallowed = blackHole || (fromServer && dropFromServer)
                    if (open && !swallowed) delayed.send(buffer.copyOf(n), latencyMs)
                }
            } catch (ignored: IOException) {
                // a socket of the link was closed: the link is over
            } finally {
                // The chunks read before the end are still delivered, then the link closes.
                delayed.close()
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

    /** Cuts every connection and refuses new ones until [comeBack]. */
    fun goDown() {
        down = true
        cutAll()
    }

    /** Lets connections through again after [goDown]. */
    fun comeBack() {
        down = false
    }

    override fun close() {
        server.close()
        cutAll()
    }
}

// Writes each chunk once its latency has passed, in the order it was read, while the next chunks are read; [onEnd]
// once the last one is written or the socket broke.
private class DelayedOutput(private val output: OutputStream, private val onEnd: () -> Unit) {
    private class Chunk(val bytes: ByteArray, val dueNanos: Long)

    private val queue = LinkedBlockingQueue<Chunk>()

    // An empty chunk ends the writer.
    private val end = Chunk(ByteArray(0), 0)

    init {
        thread(isDaemon = true, name = "chaos-delay") { write() }
    }

    fun send(bytes: ByteArray, latencyMs: Long) {
        queue.put(Chunk(bytes, System.nanoTime() + latencyMs * NANOS_PER_MILLI))
    }

    fun close() {
        queue.put(end)
    }

    private fun write() {
        try {
            var chunk = queue.take()
            while (chunk !== end) {
                val waitMs = (chunk.dueNanos - System.nanoTime()) / NANOS_PER_MILLI
                if (waitMs > 0) Thread.sleep(waitMs)
                output.write(chunk.bytes)
                output.flush()
                chunk = queue.take()
            }
        } catch (ignored: IOException) {
            // the socket written to was closed
        } finally {
            onEnd()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
