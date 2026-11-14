package org.example.kotlineditor.lsp.provision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
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
    fun `a PHP without the extensions of phpactor makes it unavailable, with the packages to install`() {
        val details =
            registry(RuntimeKind.Php to "8.5.1\nCore,posix,tokenizer")
                .candidates("php")
                .firstOrNull { it.candidate.id == "phpactor" }
                ?.details
        val detail = details?.singleOrNull().orEmpty()
        assertTrue(detail.startsWith("PHP 8.5.1 at "), detail)
        assertTrue(
            detail.contains("lacks mbstring: install it (apt install php8.5-mbstring, dnf install php-mbstring)"),
            detail,
        )
    }

    @Test
    fun `the command of an available candidate uses the found runtime and the installed artifact`() {
        val status =
            registry(
                RuntimeKind.Php to "8.3.6\nmbstring,posix,tokenizer",
                RuntimeKind.Node to "20.1.0",
            ).candidates("php")
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

    @Test
    fun `a choice made while another program reads the file is saved once it is done`() {
        val file = directory.resolve("choices.properties")
        val store = LanguageServerChoiceStore(file)
        store.choose("php", "phpactor")
        // Windows refuses to replace a file that is open, even for reading: the reader lets go a moment later.
        val reading = Files.newInputStream(file)
        val reader =
            thread {
                Thread.sleep(READ_MS)
                reading.close()
            }
        store.choose("php", "intelephense")
        reader.join()
        assertEquals("intelephense", LanguageServerChoiceStore(file).choice("php"))
    }

    @Test
    fun `every server shows its pinned version and the GitHub page it comes from`() {
        val candidates = PhpServers.all + WebServers.all
        for (candidate in candidates) {
            assertTrue(candidate.homepage.orEmpty().startsWith("https://github.com/"), candidate.id)
            assertTrue(candidate.version.orEmpty().isNotBlank(), candidate.id)
        }
        assertEquals(PhpServers.phpactor.tool?.version, PhpServers.phpactor.version)
        assertEquals(WebServers.tailwindLanguageServer.bundle?.version, WebServers.tailwindLanguageServer.version)
    }

    @Test
    fun `a language turned off keeps its chosen server, and is off for every project`() {
        val file = directory.resolve("choices.properties")
        val store = LanguageServerChoiceStore(file)
        store.choose("php", "intelephense")
        assertFalse(store.disabled("php"))

        store.setDisabled("php", true)
        val reread = LanguageServerChoiceStore(file)
        assertTrue(reread.disabled("php"))
        assertFalse(reread.disabled("vue"))
        assertEquals("intelephense", reread.choice("php", "/home/me/shop"))

        store.setDisabled("php", false)
        assertFalse(LanguageServerChoiceStore(file).disabled("php"))
    }

    private companion object {
        const val READ_MS = 300L
    }
}
