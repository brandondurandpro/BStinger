package org.example.kotlineditor.run.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.server.LocalServices
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RunManifestsTest {
    private val project: Path = Files.createTempDirectory("run-manifests")
    private val data: Path = Files.createTempDirectory("run-manifests-data")
    private val services = LocalServices(data.toFile())

    @AfterTest
    fun cleanUp() {
        services.close()
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    private fun write(relative: String, text: String) {
        val file = project.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    @Test
    fun `the manifests are found with their text and siblings, not the ones of dependencies`() {
        write("package.json", """{"scripts": {"dev": "vite"}}""")
        write("pnpm-lock.yaml", "")
        write("web/package.json", """{"scripts": {"build": "vite build"}}""")
        write("api/composer.json", """{"scripts": {"test": "phpunit"}}""")
        write("node_modules/dep/package.json", """{"scripts": {"install": "x"}}""")
        write("api/vendor/lib/composer.json", "{}")

        val manifests = runBlocking { findRunManifests(services, project.toString()) }
        val byDirectory = manifests.associateBy { it.directory }

        assertEquals(setOf("", "web", "api"), byDirectory.keys)
        assertEquals("package.json", byDirectory["web"]?.fileName)
        assertEquals("composer.json", byDirectory["api"]?.fileName)
        assertEquals("""{"scripts": {"dev": "vite"}}""", byDirectory[""]?.content)
        assertEquals(true, byDirectory[""]?.siblings?.contains("pnpm-lock.yaml"))
        assertEquals(false, byDirectory["web"]?.siblings?.contains("pnpm-lock.yaml"))
    }
}
