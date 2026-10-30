package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.model.NetworkInspector
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

private const val MAX_TRACKED = 10_000

/**
 * Feeds a [NetworkInspector] with the frames of a client's links: names the method of each frame and measures how
 * long requests wait for their answer. Shared by the successive links of a client, as requests survive reconnections.
 */
internal class NetworkTap(val inspector: NetworkInspector) {
    private class Sent(val method: Method, val atNanos: Long)

    private val requests = ConcurrentHashMap<Long, Sent>()
    private val streams = ConcurrentHashMap<Long, Method>()

    val active: Boolean get() = inspector.recording

    /** Called before the frame is written: its answer can be read before the write returns. */
    fun sending(frame: Frame) {
        val method = if (frame.kind == FrameKind.Request) methodOf(frame.body) else null
        when {
            method != null -> {
                if (requests.size >= MAX_TRACKED) requests.clear() // answers lost while not recording
                requests[frame.id] = Sent(method, System.nanoTime())
                if (method == Method.TerminalStart) streams[frame.id] = method
            }

            frame.kind == FrameKind.Cancel -> {
                requests.remove(frame.id)
            }
        }
    }

    fun sent(frame: Frame, wireSize: Int) {
        val method = if (frame.kind == FrameKind.Request) methodOf(frame.body) else null
        inspector.record(
            Direction.Sent,
            frame.kind.name,
            frame.id,
            method?.name ?: requests[frame.id]?.method?.name,
            frame.body,
            wireSize,
            redacted = frame.kind == FrameKind.Hello, // carries the token
        )
    }

    fun received(frame: Frame, wireSize: Int) {
        var latencyMs: Long? = null
        val method =
            when (frame.kind) {
                FrameKind.Response, FrameKind.Error -> {
                    requests.remove(frame.id)?.let {
                        latencyMs = (System.nanoTime() - it.atNanos) / NANOS_PER_MILLI
                        it.method
                    }
                }

                FrameKind.Event -> {
                    streams[frame.id]
                }

                FrameKind.End -> {
                    streams.remove(frame.id)
                }

                else -> {
                    null
                }
            }
        inspector.record(Direction.Received, frame.kind.name, frame.id, method?.name, frame.body, wireSize, latencyMs)
    }

    private fun methodOf(body: ByteArray): Method? =
        if (body.size < Short.SIZE_BYTES) null else Method.entries.getOrNull(WireReader(body).short())

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Counts the bytes read through it, to know the size of a frame on the socket. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

/** Counts the bytes written through it, to know the size of a frame on the socket. */
internal class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len) // FilterOutputStream would write byte by byte
        count += len
    }
}
