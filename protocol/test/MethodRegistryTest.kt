package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MethodRegistryTest {
    private fun decode(rpcName: String): Frame {
        val request = Frame(FrameKind.Request, 1, JsonObject(emptyMap()), PluginMethod("probe", "x", mutating = false))
        val message = JsonRpc.message(request)
        val renamed = JsonObject(message + ("method" to kotlinx.serialization.json.JsonPrimitive(rpcName)))
        return JsonRpc.frameOf(renamed, ByteArray(0))
    }

    @Test
    fun `a plugin method is named under its plugin`() {
        val method = PluginMethod("todo", "scan", mutating = false)
        assertEquals("plugin/todo/scan", method.rpcName)
    }

    @Test
    fun `a built-in method resolves by its name`() {
        assertSame(Method.Read, MethodRegistry.resolve(Method.Read.rpcName))
    }

    @Test
    fun `a registered plugin method resolves until it is unregistered`() {
        val method = PluginMethod("registered", "scan", mutating = true)
        val registration = MethodRegistry.register(listOf(method))
        assertSame(method, decode(method.rpcName).method)
        registration.close()
        assertNull(decode(method.rpcName).method)
    }

    @Test
    fun `a name taken twice is refused and registers nothing`() {
        val first = PluginMethod("twice", "a", mutating = false)
        val second = PluginMethod("twice", "b", mutating = false)
        val registration = MethodRegistry.register(listOf(first))
        assertFailsWith<IllegalArgumentException> {
            MethodRegistry.register(listOf(second, PluginMethod("twice", "a", mutating = false)))
        }
        assertNull(MethodRegistry.resolve(second.rpcName))
        registration.close()
    }
}
