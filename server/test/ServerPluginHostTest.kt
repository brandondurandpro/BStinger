package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlineditor.plugin.core.UnloadOutcome
import org.example.kotlineditor.rpc.MethodRegistry
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.testkit.PluginFixture
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SCAN = PluginMethod("srvprobe", "scan", mutating = false)

// The server half answers plugin/srvprobe/scan with the text "todo" and a counter of its calls.
private val ANSWERS =
    """
    c.handle(
        new org.example.kotlineditor.rpc.PluginMethod("srvprobe", "scan", false),
        (request, continuation) -> kotlinx.serialization.json.JsonElementKt.JsonPrimitive("todo"));
    """.trimIndent()

private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ServerPluginHostTest {
    private val directory: File = Files.createTempDirectory("server-plugins").toFile()
    private val handlers = HashMap<MethodSpec, Handler>()
    private val host = ServerPluginHost(directory, handlers)

    @AfterTest
    fun cleanup() {
        host.close()
        directory.deleteRecursively()
    }

    private fun install(body: String = ANSWERS, version: String = "1.0") {
        val half = PluginFixture.serverHalf("srvprobe", body, version)
        host.install(half.manifest, half.jar, sha256(half.jar))
    }

    private fun call(): String {
        val handler = assertNotNull(handlers[SCAN], "plugin/srvprobe/scan has no handler")
        val searches = SearchRegistry(CoroutineScope(Dispatchers.Default)) {}
        val request = Request(1, JsonObject(emptyMap()), searches, SessionStates {})
        return runBlocking { handler(request) }.jsonPrimitive.content
    }

    @Test
    fun `an installed server half answers its methods and is named in the list`() {
        install()

        assertEquals("todo", call())
        val listed = host.list().jsonArray.map { it.jsonObject }
        assertEquals(listOf("srvprobe"), listed.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(JsonPrimitive("1.0"), listed.single().getValue("version"))
    }

    @Test
    fun `a damaged jar is refused before it runs`() {
        val half = PluginFixture.serverHalf("srvprobe", ANSWERS)
        assertFailsWith<IllegalArgumentException> { host.install(half.manifest, half.jar, "0".repeat(64)) }
        assertNull(handlers[SCAN])
    }

    @Test
    fun `unloading takes the methods away`() {
        install()

        assertEquals(UnloadOutcome.Clean, host.unload("srvprobe"))
        assertNull(handlers[SCAN])
        assertNull(MethodRegistry.resolve(SCAN.rpcName))
        assertTrue(!File(directory, "srvprobe").exists())
    }

    @Test
    fun `installing a new version replaces the old one`() {
        install(version = "1.0")
        install(body = ANSWERS.replace("todo", "todo2"), version = "2.0")

        assertEquals("todo2", call())
        assertEquals(1, host.list().jsonArray.size)
    }

    @Test
    fun `what is installed loads again when the server restarts`() {
        install()
        host.close()
        handlers.clear()

        val restarted = ServerPluginHost(directory, handlers)
        restarted.loadInstalled()
        restarted.use { assertEquals("todo", call()) }
    }

    @Test
    fun `a half that declares a method outside its id is refused`() {
        val body = ANSWERS.replace("srvprobe", "other")
        assertFailsWith<IllegalArgumentException> { install(body) }
        assertNull(handlers[SCAN])
    }
}

private fun ServerPluginHost.use(block: () -> Unit) {
    try {
        block()
    } finally {
        close()
    }
}
