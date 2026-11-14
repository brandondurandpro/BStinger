package org.example.kotlineditor.lsp.provision

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarArchiveTest {
    // A ustar header of a regular file called [name] of [size] bytes; the checksum is not read by the extractor.
    private fun header(name: String, size: Long, mode: String = "0000644"): ByteArray {
        val block = ByteArray(512)
        name.toByteArray().copyInto(block)
        (mode + "\u0000").toByteArray().copyInto(block, 100)
        val octal = size.toString(8).padStart(11, '0') + "\u0000"
        octal.toByteArray().copyInto(block, 124)
        block[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(block, 257)
        return block
    }

    @Test
    fun `a file is extracted with its content`() {
        val content = "hello".toByteArray()
        val tar = ByteArrayOutputStream()
        tar.write(header("package/a.txt", content.size.toLong()))
        tar.write(content.copyOf(512))
        tar.write(ByteArray(1024))
        val directory = Files.createTempDirectory("tar-test")
        try {
            TarArchive.extract(ByteArrayInputStream(tar.toByteArray()), directory)
            assertEquals("hello", directory.resolve("package/a.txt").toFile().readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // The native binary of Claude Code (about 340 MB) is a single entry of the archive of its ACP agent: the size
    // limit must let it through. The stream ends right after the header, so the extraction fails as truncated, not as
    // an invalid size.
    @Test
    fun `an entry of several hundreds of megabytes has a valid size`() {
        val directory = Files.createTempDirectory("tar-test")
        try {
            val big = header("claude", 341_914_128L)
            val failure = assertFailsWith<IOException> { TarArchive.extract(ByteArrayInputStream(big), directory) }
            assertTrue("Truncated" in failure.message.orEmpty(), "the size was accepted: ${failure.message}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an entry larger than the limit is refused`() {
        val directory = Files.createTempDirectory("tar-test")
        try {
            val huge = header("huge", 16L * 1024 * 1024 * 1024)
            val failure = assertFailsWith<IOException> { TarArchive.extract(ByteArrayInputStream(huge), directory) }
            assertEquals("Invalid tar entry size", failure.message)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file that is executable in the archive is executable once extracted, the others are not`() {
        val tar = ByteArrayOutputStream()
        for ((name, mode) in listOf("run" to "0000755", "data.txt" to "0000644")) {
            tar.write(header(name, 1, mode))
            tar.write(ByteArray(512))
        }
        tar.write(ByteArray(1024))
        val directory = Files.createTempDirectory("tar-test")
        try {
            TarArchive.extract(ByteArrayInputStream(tar.toByteArray()), directory)
            assertTrue(Files.isExecutable(directory.resolve("run")))
            // Windows has no executable bit: every file it can read is executable there.
            val hasExecutableBit = "posix" in directory.fileSystem.supportedFileAttributeViews()
            if (hasExecutableBit) assertFalse(Files.isExecutable(directory.resolve("data.txt")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
