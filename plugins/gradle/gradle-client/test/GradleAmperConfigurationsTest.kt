package org.example.kotlineditor.run

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.RunConfiguration
import org.example.kotlineditor.state.RunConfigurationsSource
import org.example.kotlineditor.state.RunKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleAmperConfigurationsTest {
    private val sources = GradleAmperConfigurations()

    @Test
    fun `an Amper project and a Gradle project each suggest tests and a run`() {
        val entries = listOf("kotlin", "gradlew", "README.md").map { entry(it) }
        val suggestions = runBlocking { sources.configurations(entries, windows = false) }
        val commands = suggestions.map { it.commandLine }
        assertEquals(listOf("./kotlin test", "./kotlin run", "./gradlew test", "./gradlew run"), commands)
        assertEquals(listOf(RunKind.Tests, RunKind.Run, RunKind.Tests, RunKind.Run), suggestions.map { it.kind })
    }

    @Test
    fun `a Windows server gets the bat wrappers`() {
        val entries = listOf("kotlin").map { entry(it) }
        val commands = runBlocking { sources.configurations(entries, windows = true).map { it.commandLine } }
        assertEquals(listOf(".\\kotlin.bat test", ".\\kotlin.bat run"), commands)
    }

    @Test
    fun `a project without wrapper suggests nothing`() {
        val entries = listOf("README.md").map { entry(it) }
        assertTrue(runBlocking { sources.configurations(entries, windows = false) }.isEmpty())
    }

    private fun entry(name: String) = FileEntry(path = "/p/$name", name = name, isDirectory = false)
}
