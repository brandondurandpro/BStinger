package org.example.kotlineditor.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Way a frame went, seen from the client. */
enum class Direction {
    /** From the client to the server. */
    Sent,

    /** From the server to the client. */
    Received,
}

/** One frame seen on the link with the server (devtools). */
class NetworkRecord(
    /** Position in the capture, increasing. */
    val seq: Long,
    /** When the frame was sent or received (epoch milliseconds). */
    val timeMs: Long,
    /** Sent or received. */
    val direction: Direction,
    /** Frame kind (`Request`, `Response`, `Event`, `Ping`...). */
    val kind: String,
    /** Request or stream id, 0 when the kind has none. */
    val id: Long,
    /** Method of the request, of the request answered, or of the request that opened the stream. */
    val method: String?,
    /** Size of the payload, uncompressed. */
    val size: Int,
    /** Bytes on the socket: length prefix, header and payload after compression. */
    val wireSize: Int,
    /** Payload, cut to [NetworkInspector.MAX_BODY] bytes ([truncated]) and empty when [redacted]. */
    val body: ByteArray,
    /** Time since the request was sent, for answers. */
    val latencyMs: Long? = null,
    /** The payload holds a secret (the token of `Hello`) and was not kept. */
    val redacted: Boolean = false,
) {
    /** Whether [body] is only the start of the payload. */
    val truncated: Boolean get() = !redacted && body.size < size
}

/**
 * Capture of the frames exchanged with the server, shown by the Network devtools panel.
 *
 * Nothing is captured until [recording] is on; then the last frames are kept, within [MAX_RECORDS] frames and
 * [MAX_BYTES] of payload, so a long capture never grows without bound.
 */
class NetworkInspector {
    /** Whether frames are captured; off, the network code only reads this flag. */
    @Volatile
    var recording = false

    private val records = ArrayDeque<NetworkRecord>()
    private var bytes = 0L
    private var nextSeq = 0L
    private val mutableTotals = MutableStateFlow(NetworkTotals())

    /** Totals since the last [clear], including the frames no longer kept; changes with every frame. */
    val totals: StateFlow<NetworkTotals> = mutableTotals.asStateFlow()

    /** Adds a frame to the capture; ignored when not [recording]. */
    fun record(
        direction: Direction,
        kind: String,
        id: Long,
        method: String?,
        body: ByteArray,
        wireSize: Int,
        latencyMs: Long? = null,
        redacted: Boolean = false,
    ) {
        if (!recording) return
        val kept =
            when {
                redacted -> EMPTY
                body.size > MAX_BODY -> body.copyOf(MAX_BODY)
                else -> body
            }
        synchronized(records) {
            val record =
                NetworkRecord(
                    nextSeq++,
                    System.currentTimeMillis(),
                    direction,
                    kind,
                    id,
                    method,
                    body.size,
                    wireSize,
                    kept,
                    latencyMs,
                    redacted,
                )
            records.addLast(record)
            bytes += kept.size
            while (records.size > MAX_RECORDS || bytes > MAX_BYTES) bytes -= records.removeFirst().body.size
            mutableTotals.value = mutableTotals.value + record
        }
    }

    /** The frames kept, oldest first. */
    fun snapshot(): List<NetworkRecord> = synchronized(records) { records.toList() }

    /** Forgets every frame and resets the totals. */
    fun clear() {
        synchronized(records) {
            records.clear()
            bytes = 0
            mutableTotals.value = NetworkTotals()
        }
    }

    /** Capture limits. */
    companion object {
        /** Frames kept at most. */
        const val MAX_RECORDS = 2_000

        /** Payload bytes kept at most, all frames together. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** Payload bytes kept for one frame. */
        const val MAX_BODY = 256 * 1024

        private val EMPTY = ByteArray(0)
    }
}

/** Counters of a capture. */
data class NetworkTotals(
    /** Frames captured. */
    val frames: Long = 0,
    /** Payload bytes sent, uncompressed. */
    val sentBytes: Long = 0,
    /** Payload bytes received, uncompressed. */
    val receivedBytes: Long = 0,
    /** Bytes sent on the socket. */
    val sentWireBytes: Long = 0,
    /** Bytes received from the socket. */
    val receivedWireBytes: Long = 0,
    /** Number of the last frame captured, -1 before the first one: tells that the capture changed. */
    val lastSeq: Long = -1,
)

private operator fun NetworkTotals.plus(record: NetworkRecord): NetworkTotals {
    val sent = record.direction == Direction.Sent
    return copy(
        frames = frames + 1,
        sentBytes = sentBytes + if (sent) record.size else 0,
        receivedBytes = receivedBytes + if (sent) 0 else record.size,
        sentWireBytes = sentWireBytes + if (sent) record.wireSize else 0,
        receivedWireBytes = receivedWireBytes + if (sent) 0 else record.wireSize,
        lastSeq = record.seq,
    )
}
