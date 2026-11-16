package org.example.kotlineditor.editorconfig.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorConfigCheckerTest {
    private val root: Path = Files.createTempDirectory("editorconfig-check")

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

    private fun config(vararg lines: String) {
        file(".editorconfig", lines.joinToString("\n"))
    }

    @Test
    fun `an indent that is not a multiple of indent_size is a violation of the line`() {
        config("root = true", "[*.kt]", "indent_style = space", "indent_size = 2")
        file("src/App.kt", "package demo\n\nclass App {\n   fun off() {}\n}\n")

        val violations = checkFiles(root, listOf("src/App.kt"))

        assertEquals(listOf(EditorConfigViolation("src/App.kt", 4, "indent-size", "The indent is 3 wide, not a multiple of indent_size (2)")), violations)
    }

    @Test
    fun `a tab counts four wide and a well cut indent is no violation`() {
        config("[*.kt]", "indent_style = space", "indent_size = 2")
        file("src/App.kt", "class App {\n\tfun tab() {}\n  fun narrow() {}\n}\n")

        val violations = checkFiles(root, listOf("src/App.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a tab where spaces are asked is one violation for the width it makes`() {
        config("[*.kt]", "indent_style = space", "indent_size = 3")
        file("src/App.kt", "class App {\n\tfun tab() {}\n}\n")

        val violations = checkFiles(root, listOf("src/App.kt"))

        assertEquals(
            listOf(
                EditorConfigViolation("src/App.kt", 2, "indent-size", "The indent is 4 wide, not a multiple of indent_size (3)"),
            ),
            violations,
        )
    }

    @Test
    fun `a space where tabs are asked is a violation of the style`() {
        config("[*.kt]", "indent_style = tab")
        file("src/App.kt", "class App {\n  fun spaces() {}\n\tfun tab() {}\n}\n")

        val violations = checkFiles(root, listOf("src/App.kt"))

        assertEquals(
            listOf(
                EditorConfigViolation("src/App.kt", 2, "indent-style", "The line is indented with a space, not with tabs"),
            ),
            violations,
        )
    }

    @Test
    fun `blank lines and lines that start with something else are not checked`() {
        config("[*]", "indent_style = space", "indent_size = 2")
        file("App.kt", "\n  ok\n\n\ttab: 4 wide, a multiple of 2\n")

        val violations = checkFiles(root, listOf("App.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `the line endings of the whole file are one violation at line 0`() {
        config("[*]", "end_of_line = lf")
        file("App.kt", "first\r\nsecond\r\n")

        val violations = checkFiles(root, listOf("App.kt"))

        assertEquals(
            listOf(EditorConfigViolation("App.kt", 0, "end-of-line", "The file uses CRLF line endings, .editorconfig asks for LF")),
            violations,
        )
    }

    @Test
    fun `a missing final newline is one violation at line 0`() {
        config("[*]", "insert_final_newline = true")
        file("App.kt", "first\nsecond")

        val violations = checkFiles(root, listOf("App.kt"))

        assertEquals(
            listOf(EditorConfigViolation("App.kt", 0, "final-newline", "The file does not end with a newline")),
            violations,
        )
    }

    @Test
    fun `a file that ends with a newline and an empty file are fine`() {
        config("[*]", "insert_final_newline = true")
        file("App.kt", "first\nsecond\n")
        file("Empty.kt", "")

        val violations = checkFiles(root, listOf("App.kt", "Empty.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `the violations of a file are capped`() {
        config("[*]", "indent_style = space", "indent_size = 7")
        val lines = (1..30).joinToString("\n") { "    line $it" }
        file("App.kt", lines)

        val violations = checkFiles(root, listOf("App.kt"))

        assertEquals(20, violations.size)
        assertEquals(1, violations.first().line)
        assertEquals(20, violations.last().line)
    }

    @Test
    fun `a file over one megabyte is skipped`() {
        config("[*]", "indent_style = space", "indent_size = 2")
        file("Huge.kt", "    wide\n")
        val huge = root.resolve("Huge.kt")
        huge.writeBytes(ByteArray(1024 * 1024 + 1) { ' '.code.toByte() })

        val violations = checkFiles(root, listOf("Huge.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a binary file is skipped`() {
        config("[*]", "end_of_line = lf")
        root.resolve("logo.png").writeBytes(byteArrayOf(-119, 80, 78, 71, 0, 0, 0, 13, 0, 1))

        val violations = checkFiles(root, listOf("logo.png"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a file outside the root or missing is skipped`() {
        config("[*]", "indent_style = space", "indent_size = 2")

        val violations = checkFiles(root, listOf("../outside/App.kt", "missing/App.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `without a config file nothing is checked`() {
        file("App.kt", "\twide\n")

        val violations = checkFiles(root, listOf("App.kt"))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `the paths are taken in the order given and keep their separators`() {
        config("[*.md]", "end_of_line = lf")
        file("notes.md", "one\r\n")

        val violations = checkFiles(root, listOf("App.kt", "notes.md"))

        assertEquals(listOf("notes.md"), violations.map { it.path })
    }
}
