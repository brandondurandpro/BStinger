package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.compression.BodyCodec
import java.io.IOException

/**
 * Above this size (estimated from its params) a request is encoded and compressed apart, and smaller frames queued
 * after it may be written before it: a large save does not hold back a completion or a keystroke.
 */
internal const val LARGE_FRAME_CHARS = 256 * 1024

// Characters around a string in JSON (its quotes) and around a member of an object (quotes, colon, comma).
private const val STRING_OVERHEAD = 2
private const val MEMBER_OVERHEAD = 4

/** What a connection's writer sends: a request waiting for its answer, or a notification of the link. */
private sealed interface Outgoing {
    val frame: Frame

    class Request(val call: PendingRequests.Pending) : Outgoing {
        override val frame: Frame get() = call.frame
    }

    /** A cancellation or a pong: sent once, on whatever connection is up. */
    class Notice(override val frame: Frame) : Outgoing
}

/**
 * The frames the client sends, and the writer that sends them on each connection, off the caller's thread: a call
 * only queues its request, the writer encodes, compresses and writes it on [Dispatchers.IO]. The writer also sends the
 * heartbeat, a ping queued when nothing was sent for [heartbeatMs].
 *
 * Frames go in the order they were queued, except that a large one ([LARGE_FRAME_CHARS]) is prepared apart and may be
 * overtaken meanwhile by smaller ones; the frames of the methods the server runs in arrival order ([keepsOrder]) never
 * overtake each other. After a reconnection the writer first sends again the pending requests, in id order, and never
 * sends a request twice on one connection.
 */
internal class Outbox(private val pending: PendingRequests, private val heartbeatMs: Long) {
    private val queue = Channel<Outgoing>(Channel.UNLIMITED)

    /** Queues a registered request; it is sent on the current connection, or on the next one. */
    fun request(call: PendingRequests.Pending) {
        queue.trySend(Outgoing.Request(call))
    }

    /** Queues a notification (cancellation, pong). */
    fun notify(frame: Frame) {
        queue.trySend(Outgoing.Notice(frame))
    }

    /**
     * Writes on [link], the connection number [connection], until it breaks (then throws [IOException]) or the caller
     * is cancelled. One writer at a time: the next connection's starts once this one returned.
     */
    suspend fun drain(link: Link, connection: Long) = ConnectionWriter(link, connection).run()

    private class Entry(val item: Outgoing, val ordered: Boolean, val prepared: Deferred<Link.Prepared?>?) {
        val call: PendingRequests.Pending? get() = (item as? Outgoing.Request)?.call
    }

    private inner class ConnectionWriter(private val link: Link, private val connection: Long) {
        // Queued for this connection, in order; a request appears at most once.
        private val waiting = ArrayDeque<Entry>()
        private val waitingCalls = HashSet<PendingRequests.Pending>()

        // Large frames are compressed one at a time, in order, with their own codec: the link's keeps serving the
        // small ones meanwhile.
        private val largeLock = Mutex()
        private var largeCodec: BodyCodec? = null

        suspend fun run() {
            try {
                coroutineScope {
                    // Unanswered requests first, in their original order: the server deduplicates the retries.
                    pending.inOrder().forEach { add(this, Outgoing.Request(it)) }
                    launch { heartbeat() }
                    while (true) step(this)
                }
            } finally {
                // The preparations, children of the scope above, are over.
                largeCodec?.close()
            }
        }

        // Queues a ping whenever nothing was sent for [heartbeatMs]. Apart from the writer, so that waiting for the
        // next frame does not arm (and cancel) a timer per frame: on a busy link that cost a wakeup of the timer thread
        // per request, and doubled the round trip on a slow machine.
        private suspend fun heartbeat() {
            while (true) {
                val idleMs = System.currentTimeMillis() - link.lastSent
                val untilDue = heartbeatMs - idleMs
                if (untilDue > 0) {
                    delay(untilDue)
                } else {
                    notify(Frame(FrameKind.Ping, 0))
                    // Not before the ping is written: a writer busy with a large frame would get a ping per check.
                    delay(heartbeatMs)
                }
            }
        }

        // [writer] is the scope of the preparations: not the scope of a wait, which would wait for them.
        private suspend fun step(writer: CoroutineScope) {
            receiveQueued(writer)
            val next = nextWritable()
            if (next != null) {
                write(next)
                return
            }
            // Nothing to write now: wait for a new frame, or for the preparation holding the others.
            val holding = waiting.firstOrNull { it.prepared?.isCompleted == false }?.prepared
            if (holding == null) {
                add(writer, queue.receive())
                return
            }
            // A select that takes a frame from the queue keeps it: it is not cancelled by a timeout.
            select {
                queue.onReceive { add(writer, it) }
                holding.onAwait { }
            }
        }

        private fun receiveQueued(writer: CoroutineScope) {
            var item = queue.tryReceive().getOrNull()
            while (item != null) {
                add(writer, item)
                item = queue.tryReceive().getOrNull()
            }
        }

        private fun add(writer: CoroutineScope, item: Outgoing) {
            val call = (item as? Outgoing.Request)?.call
            val duplicate = call != null && call in waitingCalls
            val unwanted = call?.wanted(connection) == false
            if (duplicate || unwanted) return
            if (call != null) waitingCalls += call
            val frame = item.frame
            val large = call != null && frame.payload.sizeUpTo(LARGE_FRAME_CHARS) > LARGE_FRAME_CHARS
            val prepared = if (large) writer.async(Dispatchers.Default) { prepareLarge(frame, call) } else null
            waiting.addLast(Entry(item, frame.method?.keepsOrder() == true, prepared))
        }

        private suspend fun prepareLarge(frame: Frame, call: PendingRequests.Pending?): Link.Prepared? =
            largeLock.withLock {
                val codec = largeCodec ?: Protocol.newCodec().also { largeCodec = it }
                preparedOrFailed(frame, call) { link.prepare(it, codec) }
            }

        // The first small frame that may go now; otherwise the first large one that is ready.
        private fun nextWritable(): Entry? {
            var orderedHeld = false
            var readyLarge: Entry? = null
            val entries = waiting.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                val call = entry.call
                if (call != null && !call.wanted(connection)) {
                    entries.remove()
                    waitingCalls -= call
                    entry.prepared?.cancel()
                    continue
                }
                val held = entry.ordered && orderedHeld
                val prepared = entry.prepared
                if (prepared == null && !held) return entry
                val ready = prepared?.isCompleted == true
                val candidate = ready && !held
                if (candidate && readyLarge == null) readyLarge = entry
                // A large ordered frame, ready or not, goes before the ordered frames behind it.
                if (entry.ordered && prepared != null) orderedHeld = true
            }
            return readyLarge
        }

        private suspend fun write(entry: Entry) {
            waiting.remove(entry)
            val call = entry.call
            if (call != null) waitingCalls -= call
            // Answered or given up meanwhile: nothing to send.
            if (call != null && !call.claim(connection)) return
            // A large frame is written once ready, a small one is prepared now.
            val large = entry.prepared
            val prepared =
                if (large == null) {
                    preparedOrFailed(entry.item.frame, call) { link.prepare(it) }
                } else {
                    large.await()
                }
            if (prepared != null) link.write(prepared)
        }

        // [frame] ready to write, or null when it cannot be encoded: its call fails, not the connection.
        private fun preparedOrFailed(
            frame: Frame,
            call: PendingRequests.Pending?,
            prepare: (Frame) -> Link.Prepared,
        ): Link.Prepared? =
            try {
                prepare(frame)
            } catch (@Suppress("TooGenericExceptionCaught") invalid: RuntimeException) {
                call?.result?.completeExceptionally(invalid)
                null
            }
    }
}

/** Whether the server runs this method in arrival order (terminals, documents, the files of a session). */
internal fun MethodSpec.keepsOrder(): Boolean {
    val terminal = rpcName.startsWith("terminal/")
    val document = rpcName.startsWith("doc/")
    return terminal || document || this == Method.LspUse
}

// The characters of the JSON of this element, roughly, counted until they exceed [limit]: cheap on a large string.
private fun JsonElement.sizeUpTo(limit: Int): Int {
    var total = 0
    val unvisited = ArrayDeque<JsonElement>()
    unvisited.addLast(this)
    while (total <= limit) {
        when (val element = unvisited.removeLastOrNull() ?: break) {
            is JsonPrimitive -> {
                total += element.content.length + STRING_OVERHEAD
            }

            is JsonObject -> {
                element.forEach { (key, value) ->
                    total += key.length + MEMBER_OVERHEAD
                    unvisited.addLast(value)
                }
            }

            is JsonArray -> {
                total += element.size
                unvisited.addAll(element)
            }
        }
    }
    return total
}
