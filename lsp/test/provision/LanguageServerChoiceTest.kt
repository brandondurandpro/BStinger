package org.example.kotlineditor.lsp.provision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The PHP candidates offered to the user, their availability on this machine, and the saved choice. */
class LanguageServerChoiceTest {
    private val directory = Files.createTempDirectory("choice")

    private fun slashes(path: String) = path.replace('\\', '/')

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    private fun registry(vararg found: Pair<RuntimeKind, String>): CandidateRegistry {
        val versions = found.toMap()
        val detector =
            RuntimeDetector(
                configured = versions.keys.associateWith { "/opt/${it.executable}" },
                probe = { path, _ -> versions.entries.firstOrNull { path.endsWith(it.key.executable) }?.value },
            )
        return CandidateRegistry(detector)
    }

    @Test
    fun `phpactor and intelephense are offered for PHP, with their pros and cons`() {
        val candidates = registry().candidates("php").map { it.candidate }
        assertEquals(listOf("phpactor", "intelephense"), candidates.map { it.id })
        assertTrue(candidates.all { it.strengths.isNotEmpty() && it.drawbacks.isNotEmpty() })
        assertTrue(registry().candidates("kotlin").isEmpty())
    }

    @Test
    fun `a candidate whose runtime is missing or too old is unavailable, with the reason`() {
        val statuses =
            registry(RuntimeKind.Php to "7.0.33")
                .candidates("php")
                .associateBy { it.candidate.id }
        assertFalse(statuses.getValue("phpactor").available)
        assertEquals(listOf("PHP 7.0.33 found, but 8.2 or newer is needed"), statuses.getValue("phpactor").details)
        assertFalse(statuses.getValue("intelephense").available)
        assertTrue(
            statuses
                .getValue("intelephense")
                .details
                .single()
                .startsWith("Node.js not found"),
        )
    }

    @Test
    fun `the command of an available candidate uses the found runtime and the installed artifact`() {
        val status = registry(RuntimeKind.Php to "8.3.6", RuntimeKind.Node to "20.1.0").candidates("php")
        val phpactor = status.first { it.candidate.id == "phpactor" }
        assertTrue(phpactor.available)
        assertEquals(listOf("PHP 8.3.6 found"), phpactor.details)
        val installed = Path.of("/tools/phpactor.phar")
        val phpactorCommand = phpactor.candidate.command(phpactor.runtimes, installed).map(::slashes)
        assertEquals(listOf("/opt/php", "/tools/phpactor.phar", "language-server"), phpactorCommand)
        val intelephense = status.first { it.candidate.id == "intelephense" }
        val command = intelephense.candidate.command(intelephense.runtimes, Path.of("/tools/package")).map(::slashes)
        assertEquals(listOf("/opt/node", "/tools/package/lib/intelephense.js", "--stdio"), command)
    }

    @Test
    fun `no choice is saved at first`() {
        assertNull(LanguageServerChoiceStore(directory.resolve("choices.properties")).choice("php"))
    }

    @Test
    fun `a global choice applies to every project, an override to its project only`() {
        val file = directory.resolve("choices.properties")
        val store = LanguageServerChoiceStore(file)
        store.choose("php", "phpactor")
        store.choose("php", "intelephense", projectRoot = "/work/shop")
        val reloaded = LanguageServerChoiceStore(file)
        assertEquals("phpactor", reloaded.choice("php"))
        assertEquals("phpactor", reloaded.choice("php", "/work/blog"))
        assertEquals("intelephense", reloaded.choice("php", "/work/shop"))
        reloaded.clearProjectChoice("php", "/work/shop")
        assertEquals("phpactor", reloaded.choice("php", "/work/shop"))
    }

    @Test
    fun `a corrupted file means no choice yet`() {
        val file = directory.resolve("choices.properties")
        Files.write(file, byteArrayOf(0x70, 0x68, 0x70, 0x3d, 0x5c, 0x75, 0x5a, 0x5a))
        val store = LanguageServerChoiceStore(file)
        assertNull(store.choice("php"))
        store.choose("php", "phpactor")
        assertEquals("phpactor", store.choice("php"))
    }
}
