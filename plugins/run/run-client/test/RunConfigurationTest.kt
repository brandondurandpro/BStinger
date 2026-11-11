package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunConfigurationTest {
    @Test
    fun `blanks separate the words of a command line`() {
        assertEquals(listOf("./kotlin", "test", "-m", "core"), splitCommandLine("  ./kotlin   test -m\tcore "))
    }

    @Test
    fun `quotes keep blanks in a word`() {
        val words = splitCommandLine("""java -Dname="a b" 'my file.kt' "" end""")
        assertEquals(listOf("java", "-Dname=a b", "my file.kt", "", "end"), words)
    }

    @Test
    fun `an unterminated quote runs to the end`() {
        assertEquals(listOf("echo", "a b c"), splitCommandLine("echo \"a b c"))
    }

    @Test
    fun `a backslash is a character of the word, as in a Windows path`() {
        val program = """C:\Program Files\x.exe"""
        val quoted = listOf('"' + program + '"', "a").joinToString(" ")
        assertEquals(listOf(program, "a"), splitCommandLine(quoted))
    }

    @Test
    fun `an empty command line has no word`() {
        assertTrue(splitCommandLine("   ").isEmpty())
    }

    @Test
    fun `configurations survive their saved form, including tabs and line breaks in the fields`() {
        val command = listOf("""echo "a""", """b"""").joinToString("\n")
        val configurations =
            listOf(
                RunConfiguration("a", "All tests", RunKind.Tests, "./kotlin test"),
                RunConfiguration("b", """Odd""" + "\t" + """name\""", RunKind.Run, command, "sub dir"),
            )
        assertEquals(configurations, decodeConfigurations(encodeConfigurations(configurations)))
    }

    @Test
    fun `lines that are not configurations are skipped`() {
        val unknownKind = listOf("id", "Nope", "name", "", "cmd").joinToString("\t")
        val valid = listOf("ok", "Run", "name", "", "cmd").joinToString("\t")
        val text = listOf("garbage", unknownKind, valid).joinToString("\n")
        assertEquals(listOf(RunConfiguration("ok", "name", RunKind.Run, "cmd")), decodeConfigurations(text))
    }

    @Test
    fun `nothing saved reads as no configuration`() {
        assertTrue(decodeConfigurations(null).isEmpty())
        assertTrue(decodeConfigurations("").isEmpty())
    }

    @Test
    fun `an Amper project and a Gradle project each suggest tests and a run`() {
        val suggestions = suggestedConfigurations(setOf("kotlin", "gradlew", "README.md"), windows = false)
        val commands = suggestions.map { it.commandLine }
        assertEquals(listOf("./kotlin test", "./kotlin run", "./gradlew test", "./gradlew run"), commands)
        assertEquals(listOf(RunKind.Tests, RunKind.Run, RunKind.Tests, RunKind.Run), suggestions.map { it.kind })
    }

    @Test
    fun `a Windows server gets the bat wrappers`() {
        val commands = suggestedConfigurations(setOf("kotlin"), windows = true).map { it.commandLine }
        assertEquals(listOf(".\\kotlin.bat test", ".\\kotlin.bat run"), commands)
    }

    @Test
    fun `a project without wrapper suggests nothing`() {
        assertTrue(suggestedConfigurations(setOf("README.md"), windows = false).isEmpty())
    }
}
