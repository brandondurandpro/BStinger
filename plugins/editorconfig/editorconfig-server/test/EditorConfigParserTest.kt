package org.example.kotlineditor.editorconfig.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorConfigParserTest {
    private fun parse(vararg lines: String): EditorConfigFile = parseEditorConfig(lines.joinToString("\n"))

    @Test
    fun `a section holds the rules of the files its glob names`() {
        val config =
            parse(
                "root = true",
                "",
                "[*.kt]",
                "indent_style = space",
                "indent_size = 2",
                "end_of_line = lf",
                "insert_final_newline = true",
            )

        val checks = config.checksFor("App.kt")

        assertEquals(IndentStyle.SPACE, checks.indentStyle)
        assertEquals(2, checks.indentSize)
        assertEquals(EndOfLine.LF, checks.endOfLine)
        assertEquals(true, checks.insertFinalNewline)
    }

    @Test
    fun `the keys are read with spaces around them and are case-insensitive`() {
        val config = parse("[*]", "  Indent_Style = tab ", "END_OF_LINE=crlf")

        val checks = config.checksFor("README.md")

        assertEquals(IndentStyle.TAB, checks.indentStyle)
        assertEquals(EndOfLine.CRLF, checks.endOfLine)
    }

    @Test
    fun `only the sections whose glob matches the file name count`() {
        val config =
            parse(
                "[*.kt]",
                "indent_size = 2",
                "[*.md]",
                "indent_size = 4",
            )

        assertEquals(2, config.checksFor("App.kt").indentSize)
        assertEquals(4, config.checksFor("README.md").indentSize)
        assertNull(config.checksFor("build.gradle.kts").indentSize)
        assertNull(config.checksFor("guide.md.bak").indentSize)
    }

    @Test
    fun `a later section wins over an earlier one`() {
        val config =
            parse(
                "[*]",
                "indent_size = 4",
                "[*.kt]",
                "indent_size = 2",
            )

        assertEquals(2, config.checksFor("App.kt").indentSize)
        assertEquals(4, config.checksFor("guide.md").indentSize)
    }

    @Test
    fun `a key of a matching section wins over a key of the global sections`() {
        val config =
            parse(
                "[*]",
                "indent_style = tab",
                "[App.kt]",
                "indent_style = space",
            )

        assertEquals(IndentStyle.SPACE, config.checksFor("App.kt").indentStyle)
        assertEquals(IndentStyle.TAB, config.checksFor("Other.kt").indentStyle)
    }

    @Test
    fun `unknown keys and values are ignored`() {
        val config =
            parse(
                "[*.kt]",
                "charset = utf-8",
                "indent_size = tab",
                "end_of_line = crlf",
                "insert_final_newline = maybe",
            )

        val checks = config.checksFor("App.kt")

        assertNull(checks.indentSize)
        assertNull(checks.insertFinalNewline)
        assertEquals(EndOfLine.CRLF, checks.endOfLine)
    }

    @Test
    fun `comments and lines that are not a section or a key are skipped`() {
        val config =
            parse(
                "# a comment",
                "; another comment",
                "not a key",
                "[*.kt]",
                "indent_style = space",
                "",
                "[unbalanced",
                "indent_size = 9",
            )

        val checks = config.checksFor("App.kt")

        assertEquals(IndentStyle.SPACE, checks.indentStyle)
        assertNull(checks.indentSize)
    }

    @Test
    fun `a key before any section is the root marker, not a rule`() {
        val config = parse("root = true", "indent_size = 8")

        assertTrue(config.checksFor("App.kt").indentSize == null)
    }
}
