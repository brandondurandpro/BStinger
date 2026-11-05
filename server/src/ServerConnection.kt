package org.example.kotlineditor.server

import org.example.kotlineditor.compression.BodyCodec
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.ReadLimits
import org.example.kotlineditor.rpc.WireFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private const val BUFFER_SIZE = 64 * 1024

// Frames up to this size on the wire go before the larger ones waiting: an answer of a few bytes is not held back
// behind a file of megabytes being written.
private const val SMALL_FRAME = 64 * 1024

// Small frames written in a row while a large one waits: a steady flow of small ones cannot hold it back forever.
private const val SMALL_BURST = 64

// Bytes waiting in a lane before its senders wait for the writer: the backpressure a blocking write used to give
// (a terminal publishing screens over a slow link). A lane always accepts a frame when it is empty.
private const val SMALL_LANE_BYTES = 1L shl 20
private const val LARGE_LANE_BYTES = 64L shl 20

// Compression contexts kept for the next frames; more are created while more threads send at once.
private const val IDLE_COMPRESSORS = 8

/**
 * Compression contexts lent to the threads that send frames, so that a frame is compressed on its sender's thread,
 * outside the write lock, without a native context per thread.
 */
internal class CodecPool(capacity: Int = IDLE_COMPRESSORS, private val create: () -> BodyCodec = Protocol::newCodec) {
    private val idle = LinkedBlockingQueue<BodyCodec>(capacity)

    fun <T> use(block: (BodyCodec) -> T): T {
        val codec = idle.poll() ?: create()
        val result =
            try {
                block(codec)
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                // A context that failed is not lent again.
                codec.close()
                throw failure
            }
        if (!idle.offer(codec)) codec.close()
        return result
    }
}

// Shared by every connection of the process.
private val compressors = CodecPool()

/** Frames waiting for the writer, small ones first (see [SMALL_FRAME]); terminal screens count as small ones. */
private class Outbox {
    private class Lane(val budget: Long) {
        val frames = ArrayDeque<WireFrame>()
        var bytes = 0L

        fun fits(wire: WireFrame) = bytes == 0L || bytes + wire.size <= budget
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val small = Lane(SMALL_LANE_BYTES)
    private val large = Lane(LARGE_LANE_BYTES)
    private var burst = 0
    private var writing = false
    private var closed = false

    /** Queues [wire], waiting while its lane is full; dropped once closed. */
    fun offer(wire: WireFrame) =
        lock.withLock {
            val lane = laneOf(wire)
            while (!closed && !lane.fits(wire)) changed.await()
            if (!closed) {
                lane.frames.addLast(wire)
                lane.bytes += wire.size
                changed.signalAll()
            }
        }

    /** The next frame to write, waiting for one; null once closed. */
    fun take(): WireFrame? =
        lock.withLock {
            writing = false
            changed.signalAll()
            while (!closed && isEmptyLocked()) changed.await()
            if (closed) return null
            val lane = nextLane()
            val wire = lane.frames.removeFirst()
            lane.bytes -= wire.size
            writing = true
            changed.signalAll()
            wire
        }

    fun isEmpty(): Boolean = lock.withLock { isEmptyLocked() }

    /** Waits until every frame queued was written, at most [timeoutMs]. */
    fun awaitDrained(timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (!closed && !isDrainedLocked()) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return
                changed.awaitNanos(left)
            }
        }
    }

    fun close() =
        lock.withLock {
            closed = true
            small.frames.clear()
            large.frames.clear()
            changed.signalAll()
        }

    private fun isEmptyLocked() = small.frames.isEmpty() && large.frames.isEmpty()

    private fun isDrainedLocked() = !writing && isEmptyLocked()

    private fun laneOf(wire: WireFrame): Lane {
        // The screens of a terminal stay in order, and in the lane of what the user waits for.
        val kind = wire.frame.kind
        val isStream = kind == FrameKind.Event || kind == FrameKind.End
        return if (isStream || wire.size <= SMALL_FRAME) small else large
    }

    private fun nextLane(): Lane {
        val largeWaits = !large.frames.isEmpty()
        val smallFirst = !small.frames.isEmpty() && (!largeWaits || burst < SMALL_BURST)
        burst = if (smallFirst && largeWaits) burst + 1 else 0
        return if (smallFirst) small else large
    }
}

/**
 * One client connection. Frames are encoded and compressed on the thread that sends them, then written by the
 * connection's writer thread, small ones before large ones; a failed write closes it (the session then waits for the
 * client).
 */
internal class ServerConnection(private val socket: Socket) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
    private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

    // The codec of the reading thread: the native contexts are not thread-safe and keep the dictionary loaded.
    private val codec = Protocol.newCodec()
    private val outbox = Outbox()

    @Volatile
    private var closed = false

    // After the fields it uses are set.
    init {
        thread(name = "editor-server-writer", isDaemon = true) { writeLoop() }
    }

    fun read(limits: ReadLimits = ReadLimits.Default): Frame =
        closedAsIOException { Protocol.read(input, codec, limits) }

    /** Never throws: an answer for a lost connection is dropped, the client resends the request or resumes. */
    fun send(frame: Frame) {
        if (closed) return
        try {
            val wire = compressors.use { Protocol.prepare(frame, it) }
            outbox.offer(wire)
        } catch (ignored: IOException) {
            close() // the reading thread ends; the client reconnects and retries or resumes
        }
    }

    /** Waits until the frames sent so far are written, at most [timeoutMs]: a rejection must reach the client. */
    fun awaitSent(timeoutMs: Long) = outbox.awaitDrained(timeoutMs)

    override fun close() {
        closed = true
        outbox.close()
        runCatching { codec.close() }
        runCatching { socket.close() }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val wire = outbox.take() ?: return
                // Flushed once nothing else waits: frames queued together leave in as few packets as possible.
                Protocol.write(output, wire, flush = false)
                if (outbox.isEmpty()) output.flush()
            }
        } catch (ignored: IOException) {
            // The connection is lost: the client reconnects and retries or resumes.
        } finally {
            // Whatever ended the writer, nothing waits for it any more: the senders are released, the reader ends.
            close()
        }
    }

    // A request can complete while another thread closes the connection: the closed codec then throws an
    // IllegalStateException, which must look like the closed socket it stands for.
    private inline fun <T> closedAsIOException(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            if (closed) throw IOException("Connection closed", e) else throw e
        }
}
