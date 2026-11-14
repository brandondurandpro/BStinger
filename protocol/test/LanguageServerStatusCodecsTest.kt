package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
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

    private val candidate =
        LanguageServerCandidateInfo(
            id = "phpactor",
            displayName = "phpactor",
            licence = "MIT",
            strengths = listOf("Free"),
            drawbacks = listOf("Slower"),
            available = true,
            details = emptyList(),
            version = "2026.10.07.0",
            homepage = "https://github.com/phpactor/phpactor",
        )

    @Test
    fun `the version and the GitHub page of a server travel with its candidate`() {
        val choice = LanguageServerChoice("php", "PHP", listOf(candidate), "phpactor")
        assertEquals(choice, choice.languageServerChoiceJson().languageServerChoice())
    }

    @Test
    fun `a candidate of an older server has no version and no page`() {
        val choice = LanguageServerChoice("php", "PHP", listOf(candidate), "phpactor")
        val sent = choice.languageServerChoiceJson()
        val old = sent.getValue("candidates").jsonArray.map { JsonObject(it.jsonObject - "version" - "homepage") }
        val read = JsonObject(sent + ("candidates" to JsonArray(old))).languageServerChoice()
        assertEquals(candidate.copy(version = null, homepage = null), read.candidates.single())
    }
}
