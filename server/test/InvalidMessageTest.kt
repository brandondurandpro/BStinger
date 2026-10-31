package org.example.kotlineditor.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.errorMessage
import org.example.kotlineditor.rpc.int
import java.io.BufferedInputStream
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A client that sends something that is not a JSON-RPC message gets an error, and its connection stays usable. */
class InvalidMessageTest {
    private val data = Files.createTempDirectory("server-data")
    private val server = EditorServer(LocalServices(data.toFile())).start()
    private val socket = Socket("127.0.0.1", server.port).apply { soTimeout = 10_000 }
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private val codec = Protocol.newCodec()

    @AfterTest
    fun cleanup() {
        socket.close()
        codec.close()
        server.close()
        data.toFile().deleteRecursively()
    }

    private fun sendRaw(json: String) {
        val body = json.encodeToByteArray()
        output.write("Content-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body)
        output.flush()
    }

    private fun read(): Frame = Protocol.read(input, codec)

    private fun hello() {
        val params =
            buildJsonObject {
                put("version", Protocol.VERSION)
                put("token", server.token)
            }
        Protocol.write(output, Frame(FrameKind.Hello, Protocol.HELLO_ID, params), codec)
        assertEquals(FrameKind.Welcome, read().kind)
    }

    @Test
    fun `a malformed hello is rejected`() {
        sendRaw("""{"jsonrpc":"2.0","id":0,"method":"connection/hello","params":[5,"token"]}""")
        val reject = read()
        assertEquals(FrameKind.Reject, reject.kind)
        assertEquals(Protocol.REJECTED, reject.payload.jsonObject.int("code"))
    }

    @Test
    fun `invalid messages are answered with JSON-RPC errors and the connection survives`() {
        hello()

        sendRaw("""{"jsonrpc":"2.0","id":5,"method":""")
        val parse = read()
        assertEquals(FrameKind.Error, parse.kind)
        assertEquals(Protocol.NO_ID, parse.id)
        assertEquals(Protocol.PARSE_ERROR, parse.payload.jsonObject.int("code"))
        assertEquals(JsonNull, Json.parseToJsonElement(parse.body.decodeToString()).jsonObject["id"])

        sendRaw("""{"jsonrpc":"2.0","id":6}""")
        val invalid = read()
        assertEquals(6, invalid.id)
        assertEquals(Protocol.INVALID_REQUEST, invalid.payload.jsonObject.int("code"))

        sendRaw("""{"jsonrpc":"2.0","id":7,"method":"nope/nothing"}""")
        val unknown = read()
        assertEquals(7, unknown.id)
        assertEquals(Protocol.METHOD_NOT_FOUND, unknown.payload.jsonObject.int("code"))

        sendRaw("""{"jsonrpc":"2.0","id":9,"method":"${Method.FileAt.rpcName}"}""")
        val missing = read()
        assertEquals(9, missing.id)
        assertEquals(Protocol.INVALID_PARAMS, missing.payload.jsonObject.int("code"))
        assertEquals("Missing field 'path'", missing.payload.errorMessage())

        sendRaw("""{"jsonrpc":"2.0","id":10,"method":"${Method.ListDirectory.rpcName}","params":{"directory":"/"}}""")
        assertEquals(Protocol.INVALID_PARAMS, read().payload.jsonObject.int("code"))

        sendRaw("""{"jsonrpc":"2.0","id":11,"method":"${Method.FileAt.rpcName}","params":["/a"]}""")
        assertEquals(Protocol.INVALID_PARAMS, read().payload.jsonObject.int("code"))

        // Still connected: a valid request is answered.
        sendRaw("""{"jsonrpc":"2.0","id":8,"method":"${Method.RecentLoad.rpcName}"}""")
        val answer = read()
        assertEquals(FrameKind.Response, answer.kind)
        assertEquals(8, answer.id)
    }
}
