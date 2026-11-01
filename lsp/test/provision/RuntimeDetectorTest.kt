package org.example.kotlineditor.lsp.provision

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Finding PHP and Node.js on the server machine, and checking their versions. */
class RuntimeDetectorTest {
    private val bin = Files.createTempDirectory("bin")
    private val empty = Files.createTempDirectory("empty")

    @AfterTest
    fun cleanup() {
        bin.toFile().deleteRecursively()
        empty.toFile().deleteRecursively()
    }

    private fun executable(name: String): Path =
        Files.createFile(bin.resolve(name)).also { it.toFile().setExecutable(true) }

    private fun detector(
        versions: Map<String, String>,
        isWindows: Boolean = false,
        configured: Map<RuntimeKind, String> = emptyMap(),
    ) = RuntimeDetector(
        configured = configured,
        pathEnvironment = listOf(empty.toString(), bin.toString()).joinToString(File.pathSeparator),
        isWindows = isWindows,
        pathExtensions = ".COM;.EXE;.CMD",
        probe = { path, _ -> versions[path.fileName.toString()] },
    )

    @Test
    fun `a runtime on the PATH is found with its version`() {
        executable("php")
        val status = detector(mapOf("php" to "8.3.6")).detect(RuntimeKind.Php, minimum = "8.2")
        assertEquals(RuntimeStatus.Found(RuntimeKind.Php, bin.resolve("php"), "8.3.6"), status)
    }

    @Test
    fun `a runtime older than needed is reported as too old`() {
        executable("php")
        val status = detector(mapOf("php" to "7.0.33-92+ubuntu24.04.1")).detect(RuntimeKind.Php, minimum = "8.2")
        assertEquals(RuntimeStatus.TooOld(RuntimeKind.Php, bin.resolve("php"), "7.0.33", "8.2"), status)
    }

    @Test
    fun `a missing runtime is reported as missing`() {
        val status = detector(emptyMap()).detect(RuntimeKind.Node)
        assertIs<RuntimeStatus.Missing>(status)
        assertTrue(status.reason.contains("Node.js not found"), status.reason)
    }

    @Test
    fun `on Windows the PATHEXT extensions are tried`() {
        executable("node.cmd")
        val windows = detector(mapOf("node.cmd" to "v20.11.1"), isWindows = true)
        val status = windows.detect(RuntimeKind.Node, minimum = "18")
        assertEquals(RuntimeStatus.Found(RuntimeKind.Node, bin.resolve("node.cmd"), "20.11.1"), status)
    }

    @Test
    fun `a configured path wins over the PATH, and is not replaced when it does not run`() {
        executable("php")
        val elsewhere = Files.createTempFile("my-php", "").toString()
        val versions = mapOf("php" to "8.3.0", Path.of(elsewhere).fileName.toString() to "8.4.1")
        val configured = mapOf(RuntimeKind.Php to elsewhere)
        val found = assertIs<RuntimeStatus.Found>(detector(versions, configured = configured).detect(RuntimeKind.Php))
        assertEquals("8.4.1", found.version)
        val broken = detector(mapOf("php" to "8.3.0"), configured = configured).detect(RuntimeKind.Php)
        assertIs<RuntimeStatus.Missing>(broken)
        assertTrue(broken.reason.contains("does not run"), broken.reason)
    }

    @Test
    fun `the version of a real program is read from its output`() {
        // The JVM running the tests stands in for a runtime: `java --version` prints its version on stdout.
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val detector = RuntimeDetector(configured = mapOf(RuntimeKind.Node to java))
        val status = detector.detect(RuntimeKind.Node, minimum = "17")
        assertIs<RuntimeStatus.Found>(status)
        assertTrue(RuntimeDetector.compareVersions(status.version, "17") >= 0, status.version)
    }

    @Test
    fun `versions are compared numerically`() {
        assertTrue(RuntimeDetector.compareVersions("8.10", "8.9") > 0)
        assertEquals(0, RuntimeDetector.compareVersions("8.2", "8.2.0"))
        assertTrue(RuntimeDetector.compareVersions("7.4.33", "8.2") < 0)
        assertEquals("20.11.1", RuntimeDetector.parseVersion("v20.11.1\n"))
        assertEquals(null, RuntimeDetector.parseVersion("command not found"))
    }
}
