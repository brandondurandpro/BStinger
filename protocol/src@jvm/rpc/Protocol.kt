package org.example.kotlineditor.rpc

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Kotlin Editor wire protocol: binary frames over TCP.
 *
 * TCP already retransmits lost packets and keeps bytes in order; what it cannot do is survive a broken connection.
 * The session layer handles that: after a reconnection the client presents its session id, resends the requests
 * still waiting for an answer (mutating ones are deduplicated by request id on the server, so they never run
 * twice), and resumes server-pushed streams from the last sequence number it received.
 *
 * Frame: `int length | byte kind | byte flags | long id | body` where `length` counts everything after itself.
 * `flags & 1` means the body is deflated (only for large bodies, which matters on slow remote links).
 */
object Protocol {
    /** Version announced in the handshake; client and server must speak the same one. */
    const val VERSION = 1

    /** Largest accepted frame: anything bigger is treated as a corrupted stream. */
    const val MAX_FRAME = 512 * 1024 * 1024

    /** Bodies at least this large are compressed when it saves at least [MIN_SAVING_PERCENT] percent. */
    const val COMPRESS_THRESHOLD = 16 * 1024

    /** Port used by the standalone server when none is given. */
    const val DEFAULT_PORT = 7777

    private const val HEADER = 10
    private const val FLAG_DEFLATED = 1
    private const val MIN_SAVING_PERCENT = 10
    private const val PERCENT = 100
    private const val SIZE_PREFIX = Int.SIZE_BYTES
    private const val CHUNK = 64 * 1024
    private const val EXPECTED_RATIO = 3

    /** Writes and flushes [frame]; callers serialize writes to the same stream. */
    fun write(out: DataOutputStream, frame: Frame) {
        var body = frame.body
        var flags = 0
        if (body.size >= COMPRESS_THRESHOLD) {
            val compressed = deflate(body)
            if (compressed.size * PERCENT < body.size * (PERCENT - MIN_SAVING_PERCENT)) {
                body = compressed
                flags = FLAG_DEFLATED
            }
        }
        out.writeInt(HEADER + body.size)
        out.writeByte(frame.kind.ordinal)
        out.writeByte(flags)
        out.writeLong(frame.id)
        out.write(body)
        out.flush()
    }

    /** Reads one frame, blocking; throws [IOException] on a closed or corrupted stream. */
    fun read(input: DataInputStream): Frame {
        val length = input.readInt()
        val kind = FrameKind.entries.getOrNull(input.readUnsignedByte())
        if (length !in HEADER..MAX_FRAME || kind == null) {
            throw IOException("Corrupted frame (length $length, kind $kind)")
        }
        val flags = input.readUnsignedByte()
        val id = input.readLong()
        val body = ByteArray(length - HEADER)
        input.readFully(body)
        return Frame(kind, id, if (flags and FLAG_DEFLATED != 0) inflate(body) else body)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / EXPECTED_RATIO)
            val chunk = ByteArray(CHUNK)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            return WireWriter(SIZE_PREFIX + out.size()).int(data.size).toByteArray() + out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val size = WireReader(data).int()
        val inflater = Inflater(true)
        try {
            if (size !in 0..MAX_FRAME) throw IOException("Corrupted compressed frame")
            inflater.setInput(data, SIZE_PREFIX, data.size - SIZE_PREFIX)
            val out = ByteArray(size)
            var done = 0
            var stalled = false
            while (done < size && !stalled) {
                val n = inflater.inflate(out, done, size - done)
                stalled = n == 0 && (inflater.finished() || inflater.needsInput())
                done += n
            }
            if (done != size) throw IOException("Truncated compressed frame")
            return out
        } finally {
            inflater.end()
        }
    }
}

/** Type of a frame, written as its ordinal: only append new kinds at the end. */
enum class FrameKind {
    /** Client to server, first frame: version, token, previous session id (to resume it). */
    Hello,

    /** Server to client: session id, and whether the previous session was resumed. */
    Welcome,

    /** Server to client: authentication or version failure; the connection is then closed. */
    Reject,

    /** Client to server: the id is the request id, the body holds the method and its arguments. */
    Request,

    /** Server to client: successful answer to the request with the same id. */
    Response,

    /** Server to client: failure of the request with the same id; the body holds the message. */
    Error,

    /** Server to client: the id is a stream id, the body holds the sequence number, a reset flag and lines. */
    Event,

    /** Server to client: the stream with this id is over; the body holds its last sequence number. */
    End,

    /** Liveness probe, answered by [Pong]; it also keeps idle NAT mappings alive. */
    Ping,

    /** Answer to [Ping]. */
    Pong,

    /** Client to server: the request with this id is no longer needed. */
    Cancel,
}

/** One protocol frame: [kind], [id] (request or stream id, 0 when unused) and its [body]. */
class Frame(
    /** What this frame is. */
    val kind: FrameKind,
    /** Request or stream id, 0 when the kind has none. */
    val id: Long,
    /** Encoded payload, see [WireWriter]. */
    val body: ByteArray = EMPTY,
) {
    /** Shared empty body. */
    companion object {
        /** Body of frames that carry nothing. */
        val EMPTY = ByteArray(0)
    }
}

/** Remote operations. [mutating] ones have their answer cached by the server so a retried request runs once. */
enum class Method(
    /** Whether executing the method twice would have a different effect than executing it once. */
    val mutating: Boolean = false,
) {
    /** Path to directory entry, if it is a directory. */
    ResolveDirectory,

    /** Children of a directory. */
    ListDirectory,

    /** Content of a file. */
    Read,

    /** Path to file entry, if it is a regular file. */
    FileAt,

    /** Replaces the content of a file. */
    Write(mutating = true),

    /** Creates a file (and its parent directories). */
    CreateFile(mutating = true),

    /** Creates a directory. */
    CreateDirectory(mutating = true),

    /** Renames a file or a directory. */
    Rename(mutating = true),

    /** Deletes a file or a directory tree. */
    Delete(mutating = true),

    /** Every file of a project. */
    WalkFiles,

    /** Full-text search in a project. */
    FindInFiles,

    /** Declarations of a project. */
    Symbols,

    /** Git status of the changed files. */
    GitStatus,

    /** Current Git branch. */
    GitBranch,

    /** Recently opened projects. */
    RecentLoad,

    /** Saves the recently opened projects. */
    RecentSave(mutating = true),

    /** Session (open files) of a project. */
    SessionLoad,

    /** Saves the session of a project. */
    SessionSave(mutating = true),

    /** Starts a shell; the request id becomes the id of its output stream. */
    ShellStart(mutating = true),

    /** Sends one command line to a shell. */
    ShellSend(mutating = true),

    /** Replays the shell output after a given sequence number (after a reconnection). */
    ShellResume,

    /** Stops a shell. */
    ShellDestroy(mutating = true),

    /** Differences of a file between HEAD and the working tree. */
    GitDiff,

    /** Author and date of each line of a file. */
    GitBlame,

    /** Local branches of the repository. */
    GitBranches,

    /** Checks out a branch. */
    GitCheckout(mutating = true),

    /** Stages every change and commits it. */
    GitCommit(mutating = true),

    /** Pulls the current branch (fetch and merge). */
    GitPull(mutating = true),

    /** Pushes the current branch. */
    GitPush(mutating = true),
}

/** Error raised by the server while executing a request. */
class RemoteException(message: String) : IOException(message)
