package org.example.kotlineditor.todo.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoScanTest {
    private val root: Path = Files.createTempDirectory("todo-scan")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String, content: String): Path {
        val path = root.resolve(relative)
        path.parent.toFile().mkdirs()
        path.writeText(content)
        return path
    }

    private fun binary(relative: String, bytes: ByteArray): Path {
        val path = root.resolve(relative)
        path.parent.toFile().mkdirs()
        path.writeBytes(bytes)
        return path
    }

    @Test
    fun `the markers of the text files are found with their line and their path from the root`() {
        file(
            "src/App.kt",
            listOf("package demo", "", "class App {", "  // TODO: fix me", "  fun crash() {}", "  // FIXME: crash", "}").joinToString("\n"),
        )
        file("docs/notes.md", "A note\nTODO: rewrite this\n")

        val found = todoScan(root.toString())
        val sorted = found.sortedWith(compareBy({ it.path }, { it.line }))

        assertEquals(
            listOf(
                TodoMarker("docs/notes.md", 2, "TODO: rewrite this"),
                TodoMarker("src/App.kt", 4, "// TODO: fix me"),
                TodoMarker("src/App.kt", 6, "// FIXME: crash"),
            ),
            sorted,
        )
    }

    @Test
    fun `the match is case-sensitive and needs the colon`() {
        file("src/Lower.kt", "todo: lowercase\ntodo fix\nTODOS: plural\nFIXME- no colon\n")

        assertTrue(todoScan(root.toString()).isEmpty())
    }

    @Test
    fun `the ignored directories and the files that are not text are skipped`() {
        file(".git/config", "[core]\nTODO: not scanned\n")
        file("build/generated.kt", "// TODO: not scanned\n")
        binary("logo.bin", "TODO: not a text file but these bytes hold no NUL".toByteArray() + byteArrayOf(0, 1))

        assertTrue(todoScan(root.toString()).isEmpty())
    }

    @Test
    fun `the walk stops once its budget of bytes is spent`() {
        // Both files cost more than the budget: whichever one the walk reads first (the directory lists them in no
        // given order, and not the same order on every platform) spends it, and the other one is not read at all.
        file("src/First.kt", "// TODO: scanned despite the spent budget\n" + "x".repeat(400_000))
        file("src/Second.kt", "// TODO: never reached\n" + "x".repeat(400_000))

        val found = todoScan(root.toString(), maxTotalBytes = 1_000)
        assertEquals(1, found.size, "the walk stops after the file that spent the budget: $found")
        assertEquals("// TODO: scanned despite the spent budget", found.single().text)
    }
}
