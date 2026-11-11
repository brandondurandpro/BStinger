package org.example.kotlineditor.git.server

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitBranchFromHeadTest {
    private val root: Path = Files.createTempDirectory("git-head")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `git branch is read from HEAD`() =
        runBlocking {
            File(root.toFile(), ".git").mkdirs()
            File(root.toFile(), ".git/HEAD").writeText("ref: refs/heads/feature/x\n")
            assertEquals("feature/x", CliGitClient().branch(root.toString()))
            File(root.toFile(), ".git/HEAD").writeText("0123456789abcdef\n")
            assertEquals("01234567", CliGitClient().branch(root.toString()))
            assertNull(CliGitClient().branch(root.resolve("nowhere").toString()))
        }
}
