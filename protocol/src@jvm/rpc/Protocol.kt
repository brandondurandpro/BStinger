package org.example.kotlineditor.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.compression.BodyCodec
import org.example.kotlineditor.compression.Dictionaries
import org.example.kotlineditor.compression.ZstdBodyCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Kotlin Editor wire protocol: JSON-RPC 2.0 messages over TCP.
 *
 * TCP already retransmits lost packets and keeps bytes in order; what it cannot do is survive a broken connection.
 * The session layer handles that: after a reconnection the client presents its session id, resends the requests
 * still waiting for an answer (mutating ones are deduplicated by request id on the server, so they never run
 * twice), and resumes server-pushed streams from the last sequence number it received.
 *
 * Messages are framed like the Language Server Protocol: ASCII headers, an empty line, then the UTF-8 JSON body.
 * `Content-Length` counts the bytes of the body as sent; `Content-Encoding: zstd` means the body is a zstd frame
 * (when the dictionary was used, the zstd frame carries its id).
 *
 * ```
 * Content-Length: 61\r\n
 * \r\n
 * {"jsonrpc":"2.0","id":3,"method":"fs/read","params":{...}}
 * ```
 *
 * Each [FrameKind] is one JSON-RPC message:
 * - [FrameKind.Hello] is the request `connection/hello` with id [HELLO_ID]; its answer is [FrameKind.Welcome] (a
 *   result) or [FrameKind.Reject] (an error, then the connection is closed).
 * - [FrameKind.Request], [FrameKind.Response] and [FrameKind.Error] are requests and their answers.
 * - [FrameKind.Event] and [FrameKind.End] are the notifications `stream/event` and `stream/end`, whose
 *   `stream` parameter is the frame id. A stream is created by a request (`terminal/start`, `search/findInFiles`)
 *   whose id becomes the stream id; its events are numbered from 1 by `seq`, and its end holds the last `seq`.
 *   After a reconnection the client sends `stream/resume` with the last `seq` it received; `stream/close` tells the
 *   server it no longer listens.
 * - [FrameKind.Ping], [FrameKind.Pong] and [FrameKind.Cancel] are the notifications `$/ping`, `$/pong` and
 *   `$/cancelRequest` (as in LSP, its `id` parameter is the request to cancel).
 */
object Protocol {
    /** Version announced in the handshake; client and server must speak the same one. */
    const val VERSION = 6

    /** Largest accepted message: anything bigger is treated as a corrupted stream. */
    const val MAX_FRAME = 512 * 1024 * 1024

    /** Port used by the standalone server when none is given. */
    const val DEFAULT_PORT = 7777

    /** Id of the handshake request; the ids of the other requests start at 1. */
    const val HELLO_ID = 0L

    /** JSON-RPC error code of a failed method (the range -32000..-32099 is left to implementations). */
    const val SERVER_ERROR = -32_000

    /** JSON-RPC error code of a rejected handshake. */
    const val REJECTED = -32_001

    /** JSON-RPC error code of an unknown method. */
    const val METHOD_NOT_FOUND = -32_601

    /** JSON-RPC error code of params that are missing or of the wrong type. */
    const val INVALID_PARAMS = -32_602

    /** JSON-RPC error code of a message that is not JSON. */
    const val PARSE_ERROR = -32_700

    /** JSON-RPC error code of JSON that is not a valid JSON-RPC message. */
    const val INVALID_REQUEST = -32_600

    /** Id of an error answering a message whose id could not be read; it travels as JSON null. */
    const val NO_ID = -1L

    private const val MAX_HEADER_LINE = 1024
    private const val LENGTH = "content-length"
    private const val ENCODING = "content-encoding"
    private const val ZSTD = "zstd"

    /** Creates the codec of one connection; contexts and dictionaries are not shared between connections. */
    fun newCodec(): BodyCodec = ZstdBodyCodec(Dictionaries.generic, maxSize = MAX_FRAME)

    /** Writes and flushes [frame]; callers serialize writes to the same stream. */
    fun write(out: OutputStream, frame: Frame, codec: BodyCodec) {
        val compressed = codec.compress(frame.body, frame.code)
        val body = compressed ?: frame.body
        val encoding = if (compressed != null) "Content-Encoding: $ZSTD\r\n" else ""
        out.write("Content-Length: ${body.size}\r\n$encoding\r\n".toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    /** Reads one message, blocking; throws [IOException] on a closed or corrupted stream. */
    fun read(input: InputStream, codec: BodyCodec): Frame {
        var length = -1
        var compressed = false
        while (true) {
            val line = readHeaderLine(input)
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (name) {
                LENGTH -> length = value.toIntOrNull() ?: -1
                ENCODING -> compressed = value.equals(ZSTD, ignoreCase = true)
            }
        }
        if (length !in 0..MAX_FRAME) throw IOException("Corrupted message (length $length)")
        val raw = input.readNBytes(length)
        if (raw.size < length) throw IOException("Truncated message")
        return decode(if (compressed) codec.decompress(raw) else raw)
    }

    /** The JSON-RPC message of [frame], UTF-8 encoded. */
    fun encode(frame: Frame): ByteArray = JsonRpc.message(frame).toString().encodeToByteArray()

    /** The frame of a JSON-RPC message; throws [InvalidMessageException] when it is not one this protocol knows. */
    fun decode(body: ByteArray): Frame {
        val message =
            try {
                Json.parseToJsonElement(body.decodeToString())
            } catch (e: SerializationException) {
                throw InvalidMessageException(PARSE_ERROR, "Parse error: ${e.message}", NO_ID, e)
            }
        return try {
            JsonRpc.frameOf(message.jsonObject, body)
        } catch (e: IllegalArgumentException) {
            throw InvalidMessageException(INVALID_REQUEST, "Invalid request: ${e.message}", JsonRpc.idOf(message), e)
        }
    }

    private fun readHeaderLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            when (val byte = input.read()) {
                -1 -> throw IOException("Connection closed")
                '\n'.code -> return line.toString(Charsets.US_ASCII).trimEnd('\r')
                else -> line.write(byte)
            }
            if (line.size() > MAX_HEADER_LINE) throw IOException("Corrupted message (header too long)")
        }
    }
}

/** What a message is; the mapping to JSON-RPC messages is described by [Protocol]. */
enum class FrameKind {
    /** Client to server, first message: version, token, previous session id (to resume it). */
    Hello,

    /** Server to client: session id, and whether the previous session was resumed. */
    Welcome,

    /** Server to client: authentication or version failure; the connection is then closed. */
    Reject,

    /** Client to server: the id is the request id, the payload holds the named arguments. */
    Request,

    /** Server to client: successful answer to the request with the same id. */
    Response,

    /** Server to client: failure of the request with the same id; the payload is the JSON-RPC error object. */
    Error,

    /** Server to client: the id is a stream id, the payload holds the sequence number and the event. */
    Event,

    /** Server to client: the stream with this id is over; the payload holds its last sequence number. */
    End,

    /** Liveness probe, answered by [Pong]; it also keeps idle NAT mappings alive. */
    Ping,

    /** Answer to [Ping]. */
    Pong,

    /** Client to server: the request with this id is no longer needed. */
    Cancel,
}

/**
 * One protocol message: [kind], [id] (request or stream id, 0 when unused), the [method] of a request and its
 * [payload] (params, result or error, depending on the kind).
 */
class Frame(
    /** What this message is. */
    val kind: FrameKind,
    /** Request or stream id, 0 when the kind has none. */
    val id: Long,
    /** Params of a request or notification, result of a response, error object of an error. */
    val payload: JsonElement = JsonNull,
    /** Method of a [FrameKind.Request]; null for an unknown method. */
    val method: Method? = null,
    /**
     * Whether the message carries source code: a compression hint, not part of the payload.
     *
     * It lets the codec try the code dictionary, which helps file contents but can hurt metadata.
     */
    val code: Boolean = false,
    private var encoded: ByteArray? = null,
) {
    /** The JSON-RPC message, encoded once (pending requests and cached answers are sent again as they are). */
    val body: ByteArray get() = encoded ?: Protocol.encode(this).also { encoded = it }
}

/** Remote operations. [mutating] ones have their answer cached by the server so a retried request runs once. */
enum class Method(
    /** Name of the JSON-RPC method. */
    val rpcName: String,
    /** Whether executing the method twice would have a different effect than executing it once. */
    val mutating: Boolean = false,
) {
    /** Path to directory entry, if it is a directory. */
    ResolveDirectory("fs/resolveDirectory"),

    /** Children of a directory. */
    ListDirectory("fs/list"),

    /** Content of a file. */
    Read("fs/read"),

    /** Path to file entry, if it is a regular file. */
    FileAt("fs/fileAt"),

    /** Replaces the content of a file. */
    Write("fs/write", mutating = true),

    /** Creates a file (and its parent directories). */
    CreateFile("fs/createFile", mutating = true),

    /** Creates a directory. */
    CreateDirectory("fs/createDirectory", mutating = true),

    /** Renames a file or a directory. */
    Rename("fs/rename", mutating = true),

    /** Deletes a file or a directory tree. */
    Delete("fs/delete", mutating = true),

    /** Every file of a project. */
    WalkFiles("search/walkFiles"),

    /**
     * Starts a full-text search in a project; the request id becomes the id of its stream of matches. Mutating so
     * a retried request never starts the search twice.
     */
    FindInFiles("search/findInFiles", mutating = true),

    /** Declarations of a project. */
    Symbols("search/symbols"),

    /** Git status of the changed files. */
    GitStatus("git/status"),

    /** Current Git branch. */
    GitBranch("git/branch"),

    /** Recently opened projects. */
    RecentLoad("recentProjects/load"),

    /** Saves the recently opened projects. */
    RecentSave("recentProjects/save", mutating = true),

    /** Session (open files) of a project. */
    SessionLoad("projectSession/load"),

    /** Saves the session of a project. */
    SessionSave("projectSession/save", mutating = true),

    /** Starts a terminal; the request id becomes the id of its snapshot stream. */
    TerminalStart("terminal/start", mutating = true),

    /** Writes raw bytes to a terminal. */
    TerminalWrite("terminal/write", mutating = true),

    /** Sends a key to a terminal, encoded there. */
    TerminalKey("terminal/key", mutating = true),

    /** Pastes text into a terminal. */
    TerminalPaste("terminal/paste", mutating = true),

    /** Resizes a terminal. */
    TerminalResize("terminal/resize", mutating = true),

    /** Scrolls the terminal viewport. */
    TerminalScroll("terminal/scroll", mutating = true),

    /** After a reconnection, replays what a stream pushed since the last sequence number the client received. */
    StreamResume("stream/resume"),

    /** The client no longer listens to a stream: the server stops what feeds it (a search) and forgets it. */
    StreamClose("stream/close"),

    /** Stops a terminal. */
    TerminalDestroy("terminal/destroy", mutating = true),

    /** Differences of a file between HEAD and the working tree. */
    GitDiff("git/diff"),

    /** Author and date of each line of a file. */
    GitBlame("git/blame"),

    /** Remote-tracking branches. */
    GitRemoteBranches("git/remoteBranches"),

    /** A page of the commits of a branch. */
    GitLog("git/log"),

    /** A page of the commits of a branch that match a search. */
    GitSearchLog("git/searchLog"),

    /** Message and changed files of a commit. */
    GitCommitDetails("git/commitDetails"),

    /** What a commit changed in one file. */
    GitCommitDiff("git/commitDiff"),

    /** Local branches of the repository with their remote synchronization. */
    GitBranchDetails("git/branchDetails"),

    /** Local branches of the repository. */
    GitBranches("git/branches"),

    /** Checks out a branch. */
    GitCheckout("git/checkout", mutating = true),

    /** Deletes local branches, even when they are not merged. */
    GitDeleteBranches("git/deleteBranches", mutating = true),

    /** Stages every change and commits it. */
    GitCommit("git/commit", mutating = true),

    /** Pulls the current branch (fetch and merge). */
    GitPull("git/pull", mutating = true),

    /** Pushes the current branch. */
    GitPush("git/push", mutating = true),

    /** Pushes a local branch, current or not. */
    GitPushBranch("git/pushBranch", mutating = true),

    /** Fast-forwards a local branch, current or not, to the remote branch it tracks. */
    GitPullBranch("git/pullBranch", mutating = true),

    /** Clears the screen and the scrollback of a terminal. */
    TerminalClear("terminal/clear", mutating = true),
}

/** A JSON-RPC error object `{"code": ..., "message": ...}`. */
fun rpcError(code: Int, message: String): JsonObject =
    buildJsonObject {
        put("code", code)
        put("message", message)
    }

/** Message of a JSON-RPC error object. */
fun JsonElement.errorMessage(): String =
    (this as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: "Unknown error"

/**
 * A complete message that is not a valid JSON-RPC message. The stream is still in sync (the whole body was read), so
 * the server answers it with a JSON-RPC error and keeps the connection; a client sees it as a broken link.
 */
class InvalidMessageException(
    /** JSON-RPC error code: [Protocol.PARSE_ERROR] or [Protocol.INVALID_REQUEST]. */
    val code: Int,
    message: String,
    /** Id of the invalid request, [Protocol.NO_ID] when it could not be read. */
    val id: Long,
    cause: Throwable,
) : IOException(message, cause)

/** Params of a request that are missing or of the wrong type. */
class InvalidParamsException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Error raised by the server while executing a request. */
class RemoteException(message: String) : IOException(message)
