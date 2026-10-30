package org.example.kotlineditor.rpc

import org.example.kotlineditor.compression.BodyCodec
import org.example.kotlineditor.compression.Dictionaries
import org.example.kotlineditor.compression.ZstdBodyCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Kotlin Editor wire protocol: binary frames over TCP.
 *
 * TCP already retransmits lost packets and keeps bytes in order; what it cannot do is survive a broken connection.
 * The session layer handles that: after a reconnection the client presents its session id, resends the requests
 * still waiting for an answer (mutating ones are deduplicated by request id on the server, so they never run
 * twice), and resumes server-pushed streams from the last sequence number it received.
 *
 * Frame: `int length | byte kind | byte flags | long id | body` where `length` counts everything after itself.
 * `flags & 1` means the body is a zstd frame. When the dictionary was used, the zstd frame carries its id, so the
 * reader knows which dictionary to load without a protocol flag.
 */
object Protocol {
    /** Version announced in the handshake; client and server must speak the same one. */
    const val VERSION = 3

    /** Largest accepted frame: anything bigger is treated as a corrupted stream. */
    const val MAX_FRAME = 512 * 1024 * 1024

    /** Port used by the standalone server when none is given. */
    const val DEFAULT_PORT = 7777

    private const val HEADER = 10
    private const val FLAG_COMPRESSED = 1

    /** Creates the codec of one connection; contexts and dictionaries are not shared between connections. */
    fun newCodec(): BodyCodec = ZstdBodyCodec(Dictionaries.generic, maxSize = MAX_FRAME)

    /** Writes and flushes [frame]; callers serialize writes to the same stream. */
    fun write(out: DataOutputStream, frame: Frame, codec: BodyCodec) {
        val compressed = codec.compress(frame.body, frame.code)
        val body = compressed ?: frame.body
        val flags = if (compressed != null) FLAG_COMPRESSED else 0
        out.writeInt(HEADER + body.size)
        out.writeByte(frame.kind.ordinal)
        out.writeByte(flags)
        out.writeLong(frame.id)
        out.write(body)
        out.flush()
    }

    /** Reads one frame, blocking; throws [IOException] on a closed or corrupted stream. */
    fun read(input: DataInputStream, codec: BodyCodec): Frame {
        val length = input.readInt()
        val kind = FrameKind.entries.getOrNull(input.readUnsignedByte())
        if (length !in HEADER..MAX_FRAME || kind == null) {
            throw IOException("Corrupted frame (length $length, kind $kind)")
        }
        val flags = input.readUnsignedByte()
        val id = input.readLong()
        val body = ByteArray(length - HEADER)
        input.readFully(body)
        return Frame(kind, id, if (flags and FLAG_COMPRESSED != 0) codec.decompress(body) else body)
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
    /**
     * Whether [body] is source code: a compression hint, not part of the payload.
     *
     * It lets the codec try the code dictionary, which helps file contents but can hurt metadata.
     */
    val code: Boolean = false,
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

    /** Starts a terminal; the request id becomes the id of its snapshot stream. */
    TerminalStart(mutating = true),

    /** Writes raw bytes to a terminal. */
    TerminalWrite(mutating = true),

    /** Sends a key to a terminal, encoded there. */
    TerminalKey(mutating = true),

    /** Pastes text into a terminal. */
    TerminalPaste(mutating = true),

    /** Resizes a terminal. */
    TerminalResize(mutating = true),

    /** Scrolls the terminal viewport. */
    TerminalScroll(mutating = true),

    /** Replays the last screen of a terminal after a reconnection. */
    TerminalResume,

    /** Stops a terminal. */
    TerminalDestroy(mutating = true),

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
