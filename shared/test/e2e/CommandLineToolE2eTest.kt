package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.CommandLineStatus
import org.example.kotlineditor.state.CommandLineTool
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.state.ShellFlavor
import org.example.kotlineditor.state.UserPath
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `ide` command of the shell: installed from the Settings, it starts the launcher of the app on a folder. */
@OptIn(ExperimentalTestApi::class)
class CommandLineToolE2eTest {
    private val root: Path = Files.createTempDirectory("kotlin-editor-ide-command")
    private val binDir: Path = root.resolve("bin")

    // A launcher in a folder with a space, as `Kotlin Editor` is.
    private val launcherDir: Path = Files.createDirectories(root.resolve("the app"))
    private val received: Path = root.resolve("received.txt")
    private val windows = ShellFlavor.current() == ShellFlavor.Windows

    // The script of macOS starts the app with `open`, which a test cannot stand in for: it is checked as Linux's.
    private val flavor = if (windows) ShellFlavor.Windows else ShellFlavor.Posix

    private val userPath = RecordingUserPath()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    // A program that writes the arguments it is started with where the test reads them.
    private fun fakeLauncher(name: String = "launcher"): Path {
        val file = launcherDir.resolve(if (windows) "$name.cmd" else name)
        val text = if (windows) windowsLauncher() else posixLauncher()
        Files.writeString(file, text)
        file.toFile().setExecutable(true)
        return file
    }

    private fun windowsLauncher(): String = listOf("@echo off", """echo %* > "$received"""").joinToString("\r\n")

    private fun posixLauncher(): String =
        listOf("#!/bin/sh", """printf '%s\n' "${'$'}@" > '$received'""").joinToString("\n", postfix = "\n")

    private fun toolOf(launcher: Path?) = CommandLineTool(flavor, launcher, binDir, userPath)

    private fun ComposeUiTest.openCommandLineSettings(tool: CommandLineTool): E2eApp {
        val app = launchApp(commandLineTool = tool)
        onNodeWithTag(AppTags.WELCOME_SETTINGS).performClick()
        awaitTag(AppTags.SETTINGS)
        onNodeWithTag(AppTags.settingsCategory(SettingsCategory.CommandLine.name)).performClick()
        return app
    }

    private fun ComposeUiTest.installFromSettings(tool: CommandLineTool) {
        openCommandLineSettings(tool)
        onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).performClick()
        awaitCondition { tool.status == CommandLineStatus.Installed }
    }

    private fun ComposeUiTest.textOf(tag: String): String {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(" ") { it.text }
    }

    // Runs the command as a user does: from a directory of their own, with the folder as they type it.
    private fun runIde(from: Path, folder: String?): Int {
        val script = binDir.resolve(if (windows) "ide.cmd" else "ide")
        val arguments = listOfNotNull(folder)
        val command = if (windows) listOf("cmd", "/c", script.toString()) else listOf("/bin/sh", script.toString())
        val process = ProcessBuilder(command + arguments).directory(from.toFile()).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor()
    }

    private fun ComposeUiTest.awaitReceived(): String {
        awaitCondition { Files.exists(received) && Files.size(received) > 0 }
        return Files.readString(received)
    }

    @Test
    fun `installing from the Settings writes a command that opens a relative folder from another directory`() =
        runComposeUiTest {
            val tool = toolOf(fakeLauncher())
            openCommandLineSettings(tool)
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).assertIsEnabled()
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).performClick()
            awaitCondition { tool.status == CommandLineStatus.Installed }
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_REMOVE).assertIsEnabled()
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).assertIsNotEnabled()

            val project = Files.createDirectories(root.resolve("work/project"))
            assertEquals(0, runIde(from = root.resolve("work"), folder = "project"))
            val arguments = awaitReceived()
            assertTrue("--open" in arguments, arguments)
            assertTrue(project.toRealPath().toString().lowercase() in arguments.lowercase(), arguments)
        }

    @Test
    fun `without a folder the command opens the current directory`() =
        runComposeUiTest {
            installFromSettings(toolOf(fakeLauncher()))
            val here = Files.createDirectories(root.resolve("here"))
            assertEquals(0, runIde(from = here, folder = null))
            assertTrue(here.toRealPath().toString().lowercase() in awaitReceived().lowercase())
        }

    @Test
    fun `a folder that does not exist fails and starts nothing`() =
        runComposeUiTest {
            installFromSettings(toolOf(fakeLauncher()))
            assertEquals(2, runIde(from = root, folder = "missing"))
            assertFalse(Files.exists(received), "the launcher is not started")
        }

    @Test
    fun `a command that is not ours is never overwritten`() =
        runComposeUiTest {
            Files.createDirectories(binDir)
            val foreign = binDir.resolve(if (windows) "ide.cmd" else "ide")
            Files.writeString(foreign, "echo mine")
            val tool = toolOf(fakeLauncher())
            openCommandLineSettings(tool)
            assertEquals(CommandLineStatus.Foreign, tool.status)
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).assertIsNotEnabled()
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_REMOVE).assertIsNotEnabled()
            assertEquals("echo mine", Files.readString(foreign))
        }

    @Test
    fun `removing deletes the command and leaves no file behind`() =
        runComposeUiTest {
            val tool = toolOf(fakeLauncher())
            openCommandLineSettings(tool)
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).performClick()
            awaitCondition { tool.status == CommandLineStatus.Installed }
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_REMOVE).performClick()
            awaitCondition { tool.status == CommandLineStatus.NotInstalled }
            val left = Files.list(binDir).use { it.toList() }
            assertTrue(left.isEmpty(), "left: $left")
        }

    @Test
    fun `a command that starts another copy of the app is updated only when the user asks`() =
        runComposeUiTest {
            val old = toolOf(fakeLauncher("old"))
            old.install()
            val before = Files.readString(old.scriptPath)
            val moved = fakeLauncher("new")
            val tool = toolOf(moved)
            // Starting the app, even a copy that runs once, writes nothing.
            assertEquals(CommandLineStatus.Outdated, tool.status)
            assertEquals(before, Files.readString(tool.scriptPath))
            openCommandLineSettings(tool)
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).performClick()
            awaitCondition { tool.status == CommandLineStatus.Installed }
            val script = Files.readString(tool.scriptPath)
            assertTrue(moved.toString().replace('\\', '/') in script.replace('\\', '/'), script)
        }

    @Test
    fun `the status says where the command goes, and the Windows PATH is set only there`() =
        runComposeUiTest {
            val tool = toolOf(fakeLauncher())
            openCommandLineSettings(tool)
            assertTrue(tool.scriptPath.toString() in textOf(AppTags.SETTINGS_COMMAND_LINE_STATUS))
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).performClick()
            awaitTag(AppTags.SETTINGS_COMMAND_LINE_MESSAGE)
            val message = textOf(AppTags.SETTINGS_COMMAND_LINE_MESSAGE)
            if (windows) {
                assertEquals(listOf(binDir), userPath.added)
            } else {
                // Not on the PATH of the test: the user is told the line to add, nothing is edited.
                assertTrue("export PATH" in message, message)
                assertTrue(userPath.added.isEmpty())
            }
        }

    @Test
    fun `without a launcher the Settings say it is only in the installed app`() =
        runComposeUiTest {
            val tool = toolOf(null)
            openCommandLineSettings(tool)
            assertTrue("installed app" in textOf(AppTags.SETTINGS_COMMAND_LINE_STATUS))
            onNodeWithTag(AppTags.SETTINGS_COMMAND_LINE_INSTALL).assertIsNotEnabled()
            assertFalse(Files.exists(binDir), "nothing is written")
        }
}

// The PATH of the user, which a test must not touch: it records what the tool asks for and knows nothing.
private class RecordingUserPath : UserPath {
    val added = mutableListOf<Path>()

    override fun contains(directory: Path): Boolean = directory in added

    override fun add(directory: Path) {
        added.add(directory)
    }
}
