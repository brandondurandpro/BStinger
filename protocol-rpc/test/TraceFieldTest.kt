package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.telemetry.SpanContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TraceFieldTest {
    private val params = buildJsonObject { put("path", "/p") }

    private fun request(trace: String?) =
        Frame(FrameKind.Request, 7, params, Method.Read, trace = trace)

    @Test
    fun `a request names its span to the server in a traceparent`() {
        val traceparent = SpanContext.root().traceparent()
        val message = JsonRpc.message(request(traceparent))
        assertEquals(JsonPrimitive(traceparent), message["trace"])
        val decoded = JsonRpc.frameOf(message, ByteArray(0))
        assertEquals(traceparent, decoded.trace)
        assertEquals(Method.Read, decoded.method)
        assertEquals(params, decoded.payload)
    }

    @Test
    fun `without telemetry the message is the one an older peer knows`() {
        val message = JsonRpc.message(request(null))
        assertFalse("trace" in message)
        assertNull(JsonRpc.frameOf(message, ByteArray(0)).trace)
    }

    @Test
    fun `a field the server does not know is ignored, a trace of another type too`() {
        val message = JsonRpc.message(request(null))
        val extended = JsonObject(message + ("baggage" to JsonPrimitive("x")) + ("trace" to JsonObject(emptyMap())))
        val decoded = JsonRpc.frameOf(extended, ByteArray(0))
        assertEquals(Method.Read, decoded.method)
        assertNull(decoded.trace)
    }

    @Test
    fun `the method of the consent is in the catalog`() {
        assertEquals("telemetry/config", Method.TelemetryConfig.rpcName)
    }
}
