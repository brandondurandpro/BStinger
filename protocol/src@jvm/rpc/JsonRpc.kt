package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Mapping between [Frame]s and JSON-RPC 2.0 messages, described by [Protocol]. */
internal object JsonRpc {
    private const val HELLO = "connection/hello"
    private const val EVENT = "stream/event"
    private const val END = "stream/end"
    private const val PING = "$/ping"
    private const val PONG = "$/pong"
    private const val CANCEL = "$/cancelRequest"
    private const val ID = "id"
    private const val METHOD = "method"
    private const val PARAMS = "params"
    private const val RESULT = "result"
    private const val ERROR = "error"
    private const val STREAM = "stream"

    private val methods = Method.entries.associateBy { it.rpcName }

    fun message(frame: Frame): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            when (frame.kind) {
                FrameKind.Hello -> {
                    request(Protocol.HELLO_ID, HELLO, frame.payload)
                }

                FrameKind.Request -> {
                    val method = checkNotNull(frame.method) { "Request without method" }
                    request(frame.id, method.rpcName, frame.payload)
                }

                FrameKind.Welcome, FrameKind.Response -> {
                    put(ID, frame.id)
                    put(RESULT, frame.payload)
                }

                FrameKind.Reject, FrameKind.Error -> {
                    put(ID, if (frame.id == Protocol.NO_ID) JsonNull else JsonPrimitive(frame.id))
                    put(ERROR, frame.payload)
                }

                FrameKind.Event -> {
                    notification(EVENT, streamParams(frame))
                }

                FrameKind.End -> {
                    notification(END, streamParams(frame))
                }

                FrameKind.Ping -> {
                    put(METHOD, PING)
                }

                FrameKind.Pong -> {
                    put(METHOD, PONG)
                }

                FrameKind.Cancel -> {
                    notification(CANCEL, buildJsonObject { put(ID, frame.id) })
                }
            }
        }

    /** Id of a message that could not be decoded, if it has a readable one. */
    fun idOf(message: JsonElement): Long =
        (message as? JsonObject)?.get(ID)?.let { it as? JsonPrimitive }?.longOrNull ?: Protocol.NO_ID

    /** Throws [IllegalArgumentException] when [message] is not a message of this protocol. */
    fun frameOf(message: JsonObject, body: ByteArray): Frame {
        val id = message[ID]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull
        val params = message[PARAMS] ?: JsonNull
        return when (val method = message[METHOD]?.jsonPrimitive?.content) {
            null -> answer(message, id, body)
            HELLO -> Frame(FrameKind.Hello, Protocol.HELLO_ID, params, encoded = body)
            EVENT -> streamFrame(FrameKind.Event, params, body)
            END -> streamFrame(FrameKind.End, params, body)
            PING -> Frame(FrameKind.Ping, 0, encoded = body)
            PONG -> Frame(FrameKind.Pong, 0, encoded = body)
            CANCEL -> Frame(FrameKind.Cancel, params.jsonObject.long(ID), encoded = body)
            else -> request(method, id, params, body)
        }
    }

    // An unknown method keeps a null method: the server answers it with METHOD_NOT_FOUND.
    private fun request(method: String, id: Long?, params: JsonElement, body: ByteArray): Frame {
        requireNotNull(id) { "Request without id" }
        return Frame(FrameKind.Request, id, params, methods[method], encoded = body)
    }

    // The handshake is the only request with HELLO_ID: its answer is a Welcome or a Reject.
    // An error may have a null id (its request could not be read); a result may not.
    private fun answer(message: JsonObject, id: Long?, body: ByteArray): Frame {
        val hello = id == Protocol.HELLO_ID
        return when {
            ERROR in message -> {
                val kind = if (hello) FrameKind.Reject else FrameKind.Error
                Frame(kind, id ?: Protocol.NO_ID, message.field(ERROR), encoded = body)
            }

            RESULT in message -> {
                requireNotNull(id) { "Result without id" }
                Frame(if (hello) FrameKind.Welcome else FrameKind.Response, id, message.field(RESULT), encoded = body)
            }

            else -> {
                throw IllegalArgumentException("Neither a request, a notification nor an answer")
            }
        }
    }

    private fun JsonObjectBuilder.request(id: Long, method: String, params: JsonElement) {
        put(ID, id)
        put(METHOD, method)
        if (params != JsonNull) put(PARAMS, params)
    }

    private fun JsonObjectBuilder.notification(method: String, params: JsonObject) {
        put(METHOD, method)
        put(PARAMS, params)
    }

    private fun streamParams(frame: Frame): JsonObject =
        JsonObject(mapOf(STREAM to JsonPrimitive(frame.id)) + frame.payload.jsonObject)

    private fun streamFrame(kind: FrameKind, params: JsonElement, body: ByteArray): Frame {
        val fields = params.jsonObject
        return Frame(kind, fields.long(STREAM), JsonObject(fields - STREAM), encoded = body)
    }
}
