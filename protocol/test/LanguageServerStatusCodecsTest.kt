package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.ServerMemory
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageServerStatusCodecsTest {
    private val memory =
        ServerMemory(heapUsedBytes = 10, heapMaxBytes = 100, residentBytes = 50, pid = 4242, startedAtMillis = 1_700_000, cpuPercent = 37)

    @Test
    fun `the server's memory travels with its process`() {
        val status = LanguageServersStatus(version = 3, server = memory, servers = emptyList())
        assertEquals(status, status.toJson().languageServersStatus())
    }

    @Test
    fun `a server that names no process is read as an unknown one`() {
        val sent = LanguageServersStatus(version = 3, server = memory, servers = emptyList()).toJson()
        val oldServer = JsonObject(sent.getValue("server").jsonObject - "pid" - "startedAt")
        val read = JsonObject(sent + ("server" to oldServer)).languageServersStatus()
        assertEquals(memory.copy(pid = 0, startedAtMillis = 0), read.server)
    }

    @Test
    fun `a server that does not measure its processor is read as not measured`() {
        val sent = LanguageServersStatus(version = 3, server = memory, servers = emptyList()).toJson()
        val oldServer = JsonObject(sent.getValue("server").jsonObject - "cpuPercent")
        val read = JsonObject(sent + ("server" to oldServer)).languageServersStatus()
        assertEquals(ServerMemory.NOT_MEASURED, read.server.cpuPercent)
    }
}
