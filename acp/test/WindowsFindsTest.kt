package org.example.kotlineditor.acp

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the server checks on Windows that the program of an agent exists, before cmd.exe hides its absence. */
class WindowsFindsTest {
    private val home = Files.createTempDirectory("agent-home")
    private val bin = Files.createTempDirectory("agent-bin")

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        bin.toFile().deleteRecursively()
    }

    private fun finds(program: String) = windowsFinds(program, home.toString(), "C:\\nowhere;$bin", ".EXE;.CMD")

    @Test
    fun `a name is found on the PATH with an extension of PATHEXT`() {
        Files.createFile(bin.resolve("npx.CMD"))
        assertTrue(finds("npx"))
        assertTrue(finds("npx.CMD"))
    }

    @Test
    fun `a name is found in the directory the agent starts in`() {
        Files.createFile(home.resolve("agent.EXE"))
        assertTrue(finds("agent"))
    }

    @Test
    fun `a path is found as it is, the PATH aside`() {
        val program = bin.resolve("agent.EXE")
        Files.createFile(program)
        assertTrue(finds(program.toString().removeSuffix(".EXE")))
    }

    @Test
    fun `a missing program or a directory is not found`() {
        Files.createDirectory(bin.resolve("folder"))
        assertFalse(finds("no-such-agent-42"))
        assertFalse(finds("folder"))
    }
}
