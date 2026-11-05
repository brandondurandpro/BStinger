package org.example.kotlineditor.server

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenFileTest {
    private val directory = Files.createTempDirectory("token-file")

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the token is read without its surrounding blanks`() {
        val file = directory.resolve("token")
        Files.writeString(file, "  s3cret\r\n")

        assertEquals("s3cret", TokenFile.read(file.toFile()))
    }

    @Test
    fun `an empty or missing token file is an error that names the file`() {
        val empty = directory.resolve("empty")
        Files.writeString(empty, "\n")

        val emptyFailure = assertFailsWith<IOException> { TokenFile.read(empty.toFile()) }
        assertTrue(emptyFailure.message.orEmpty().contains("empty"), emptyFailure.message)
        val missing = directory.resolve("missing").toFile()
        val missingFailure = assertFailsWith<IOException> { TokenFile.read(missing) }
        assertTrue(missingFailure.message.orEmpty().contains(missing.toString()), missingFailure.message)
    }

    @Test
    fun `a written token file is readable by its owner only`() {
        val file = directory.resolve("written")
        Files.writeString(file, "previous")

        TokenFile.write(file, "fresh")

        assertEquals("fresh", TokenFile.read(file.toFile()))
        val permissions = ServerState.permissions(file.toFile())
        if (permissions != null) assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
    }
}
