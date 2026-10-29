package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.DiffKind
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitDiffTest {
    private val root = Files.createTempDirectory("git-diff")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun git(vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private fun textOf(vararg lines: String): String = lines.joinToString("\n")

    private fun commitAll() {
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    @Test
    fun `unified diff is parsed into numbered lines`() {
        val output =
            listOf(
                "diff --git a/a.kt b/a.kt",
                "index 1111111..2222222 100644",
                "--- a/a.kt",
                "+++ b/a.kt",
                "@@ -1,3 +1,4 @@ fun main()",
                " context",
                "-old",
                "+new",
                "+added",
                " tail",
                "\\ No newline at end of file",
            )
        val lines = UnifiedDiff().parse(output)
        assertEquals(
            listOf(
                DiffKind.Hunk,
                DiffKind.Context,
                DiffKind.Removed,
                DiffKind.Added,
                DiffKind.Added,
                DiffKind.Context,
            ),
            lines.map { it.kind },
        )
        assertEquals(listOf(-1, 1, 2, -1, -1, 3), lines.map { it.oldLine })
        assertEquals(listOf(-1, 1, -1, 2, 3, 4), lines.map { it.newLine })
        assertEquals("@@ -1,3 +1,4 @@ fun main()", lines.first().text)
        assertEquals("added", lines[4].text)
    }

    @Test
    fun `a new file is entirely additions`() {
        val lines = UnifiedDiff.newFile(listOf("one", "two"))
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Added, DiffKind.Added), lines.map { it.kind })
        assertEquals(listOf(-1, 1, 2), lines.map { it.newLine })
        assertEquals(listOf("one", "two"), lines.drop(1).map { it.text })
    }

    @Test
    fun `working tree changes against HEAD are shown`() =
        runBlocking {
            root.resolve("a.kt").writeText(textOf("one", "two", "three"))
            commitAll()
            root.resolve("a.kt").writeText(textOf("one", "TWO", "three"))
            val lines = CliGitClient().diff(root.toString(), "a.kt")
            val removed = lines.single { it.kind == DiffKind.Removed }
            assertEquals("two", removed.text)
            assertEquals(2, removed.oldLine)
            val added = lines.single { it.kind == DiffKind.Added }
            assertEquals("TWO", added.text)
            assertEquals(2, added.newLine)
            assertTrue(lines.any { it.kind == DiffKind.Context && it.text == "one" })
        }

    @Test
    fun `untracked files are shown as additions`() =
        runBlocking {
            root.resolve("new.kt").writeText("x\ny\n")
            git("init", "-q")
            val lines = CliGitClient().diff(root.toString(), "new.kt")
            assertEquals(listOf(DiffKind.Hunk, DiffKind.Added, DiffKind.Added), lines.map { it.kind })
            assertEquals(listOf("x", "y"), lines.drop(1).map { it.text })
        }

    @Test
    fun `staged files are shown in a repository without commits`() =
        runBlocking {
            root.resolve("a.kt").writeText("first\n")
            git("init", "-q")
            git("add", "a.kt")
            val lines = CliGitClient().diff(root.toString(), "a.kt")
            assertEquals(listOf(DiffKind.Hunk, DiffKind.Added), lines.map { it.kind })
            assertEquals("first", lines.last().text)
        }

    @Test
    fun `deleted files show their removed lines`() =
        runBlocking {
            root.resolve("gone.kt").writeText("x\ny\n")
            commitAll()
            Files.delete(root.resolve("gone.kt"))
            val lines = CliGitClient().diff(root.toString(), "gone.kt")
            assertEquals(listOf("x", "y"), lines.filter { it.kind == DiffKind.Removed }.map { it.text })
        }

    @Test
    fun `a file without changes has an empty diff`() =
        runBlocking {
            root.resolve("a.kt").writeText("same\n")
            commitAll()
            assertTrue(CliGitClient().diff(root.toString(), "a.kt").isEmpty())
        }
}
