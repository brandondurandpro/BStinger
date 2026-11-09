package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.RunTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Run tool window: configurations, the output of their commands, and the clickable errors in it. */
@OptIn(ExperimentalTestApi::class)
class RunE2eTest {
    private val project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the Run window is empty until a configuration is added`() =
        runComposeUiTest {
            launchWithProject(project)
            openRunWindow()
            awaitTag(RunTags.EMPTY).assertIsDisplayed()
            onNodeWithTag(RunTags.START).performClick()
            // Nothing to run: nothing starts.
            awaitNoTag(RunTags.OUTPUT)
            onNodeWithTag(AppTags.TOOL_RUN).performClick()
            awaitNoTag(RunTags.RUN)
        }

    @Test
    fun `a configuration made in the editor runs and shows its output and exit code`() =
        runComposeUiTest {
            project.file("out.txt", "hello from the run\nsecond line\n")
            val app = launchWithProject(project)
            openRunWindow()
            awaitTag(RunTags.EMPTY)
            onNodeWithTag(RunTags.ADD_FIRST).performClick()
            onNodeWithTag(RunTags.EDITOR_NAME).performTextInput("Say hello")
            onNodeWithTag(RunTags.EDITOR_COMMAND).performTextInput(emit("out.txt"))
            onNodeWithTag(RunTags.EDITOR_SAVE).performClick()
            awaitNoTag(RunTags.EDITOR)
            onNodeWithTag(RunTags.SELECTOR).assertIsDisplayed()
            pressRun()
            awaitRunStatus(app, RunStatus.Succeeded)
            awaitTag(RunTags.line(0)).assertTextEquals("hello from the run")
            awaitTag(RunTags.line(1)).assertTextEquals("second line")
            onNodeWithTag(RunTags.STATUS).assertTextEquals("Finished (exit code 0)")
            assertEquals(listOf("hello from the run", "second line"), app.runOutput())
        }

    @Test
    fun `a command that fails shows its exit code`() =
        runComposeUiTest {
            project.file("out.txt", "something went wrong\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Fail", emit("out.txt", exitCode = 3))
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            onNodeWithTag(RunTags.STATUS).assertTextEquals("Failed (exit code 3)")
            assertEquals(
                3,
                app.project.run.session
                    ?.exitCode,
            )
        }

    @Test
    fun `a program that does not exist says it could not start`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Nothing", "no-such-program-in-this-project --flag")
            pressRun()
            awaitRunStatus(app, RunStatus.NotStarted)
            assertTrue(
                app.project.run.session
                    ?.error
                    ?.isNotBlank() == true,
            )
            awaitTag(RunTags.STATUS)
            assertTrue(
                app.project.run.session
                    ?.running == false,
            )
        }

    @Test
    fun `an empty command line cannot be saved`() =
        runComposeUiTest {
            launchWithProject(project)
            openRunWindow()
            awaitTag(RunTags.ADD_FIRST).performClick()
            awaitTag(RunTags.EDITOR_NAME).performTextInput("Nothing to run")
            onNodeWithTag(RunTags.EDITOR_SAVE).performClick()
            // Still editing: a blank command is refused.
            awaitTag(RunTags.EDITOR).assertIsDisplayed()
            onNodeWithTag(RunTags.EDITOR_CANCEL).performClick()
            awaitNoTag(RunTags.EDITOR)
            awaitTag(RunTags.EMPTY)
        }

    @Test
    fun `the stop button ends a command that never ends`() =
        runComposeUiTest {
            project.file("out.txt", "started and waiting\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            pressRun()
            awaitTag(RunTags.line(0)).assertTextEquals("started and waiting")
            assertEquals(
                RunStatus.Running,
                app.project.run.session
                    ?.status,
            )
            onNodeWithTag(RunTags.STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
            onNodeWithTag(RunTags.STATUS).assertTextEquals("Stopped")
        }

    @Test
    fun `running again replaces the output of the previous run`() =
        runComposeUiTest {
            project.file("out.txt", "only line\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Once", emit("out.txt"))
            pressRun()
            awaitRunStatus(app, RunStatus.Succeeded)
            val first = app.project.run.session
            pressRun()
            awaitCondition { app.project.run.session !== first }
            awaitRunStatus(app, RunStatus.Succeeded)
            assertEquals(listOf("only line"), app.runOutput())
        }

    @Test
    fun `starting a run while another one runs stops the first`() =
        runComposeUiTest {
            project.file("slow.txt", "slow\n")
            project.file("quick.txt", "quick\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Slow", emit("slow.txt", mode = "hang"))
            addConfiguration("Quick", emit("quick.txt"))
            // The slow one is selected again, and started.
            awaitTag(RunTags.SELECTOR).performClick()
            val slow =
                app.project.run.configurations
                    .first { it.name == "Slow" }
            awaitTag(RunTags.configuration(slow.id)).performClick()
            pressRun()
            awaitCondition { app.runOutput() == listOf("slow") }
            val slowRun = app.project.run.session
            awaitTag(RunTags.SELECTOR).performClick()
            val quick =
                app.project.run.configurations
                    .first { it.name == "Quick" }
            awaitTag(RunTags.configuration(quick.id)).performClick()
            pressRun()
            awaitCondition {
                app.project.run.session
                    ?.status == RunStatus.Succeeded
            }
            awaitCondition { slowRun?.status == RunStatus.Stopped }
            assertEquals(listOf("quick"), app.runOutput())
        }

    @Test
    fun `shift F10 runs the selected configuration and shows the window`() =
        runComposeUiTest {
            project.file("out.txt", "from the shortcut\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Shortcut", emit("out.txt"))
            onNodeWithTag(AppTags.TOOL_RUN).performClick()
            awaitNoTag(RunTags.RUN)
            onNodeWithTag(TreeTags.TREE).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
            awaitTag(RunTags.RUN)
            awaitRunStatus(app, RunStatus.Succeeded)
            assertEquals(listOf("from the shortcut"), app.runOutput())
        }

    @Test
    fun `a configuration can be edited and deleted`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("First", "echo-first")
            addConfiguration("Second", "echo-second")
            assertEquals(
                listOf("First", "Second"),
                app.project.run.configurations
                    .map { it.name },
            )
            awaitTag(RunTags.SELECTOR).performClick()
            awaitTag(RunTags.EDIT).performClick()
            awaitTag(RunTags.EDITOR_DELETE).performClick()
            awaitNoTag(RunTags.EDITOR)
            assertEquals(
                listOf("First"),
                app.project.run.configurations
                    .map { it.name },
            )
            assertEquals(
                "First",
                app.project.run.selected
                    ?.name,
            )
        }

    @Test
    fun `the configurations are kept with the preferences of the project`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Kept", "some-command --with \"quoted args\"", RunKind.Tests)
            val saved = app.project.run.toPreferences()
            val key = saved.keys.first { it.startsWith("run.configurations.") }
            assertTrue("Kept" in saved.getValue(key))
            assertTrue("some-command" in saved.getValue(key))
        }

    @Test
    fun `an error of the compiler in the output opens its file at the line and column`() =
        runComposeUiTest {
            val file = project.root.resolve("src/main/kotlin/App.kt")
            project.file("out.txt", "e: ${file.toUri()}:8:5 Unresolved reference: greeting\nBUILD FAILED\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Compile", emit("out.txt", exitCode = 1))
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.link(0, 0)).performClick()
            awaitTag(EditorTags.tab("src/main/kotlin/App.kt"))
            awaitEditorReady()
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(7, 4)
            }
        }

    @Test
    fun `a stack frame names only the file, which is found in the project`() =
        runComposeUiTest {
            project.file("out.txt", "    at demo.AppTest.greetingIsLoud(AppTest.kt:3)\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Stack", emit("out.txt", exitCode = 1))
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            awaitTag(RunTags.link(0, 0)).performClick()
            awaitTag(EditorTags.tab("src/test/kotlin/AppTest.kt"))
            awaitEditorReady()
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret
                    ?.line == 2
            }
        }

    @Test
    fun `a path that is not in the project opens nothing`() =
        runComposeUiTest {
            project.file("out.txt", "e: Nowhere.kt:4:1 not here\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Nowhere", emit("out.txt", exitCode = 1))
            pressRun()
            awaitRunStatus(app, RunStatus.Failed)
            val tabs = app.project.editor.activePane.tabs.size
            awaitTag(RunTags.link(0, 0)).performClick()
            waitForIdle()
            assertEquals(tabs, app.project.editor.activePane.tabs.size)
        }

    @Test
    fun `an Amper project offers its test and run configurations`() =
        runComposeUiTest {
            project.file("kotlin", "#!/bin/sh\n")
            project.file("project.yaml", "modules:\n  - app\n")
            val app = launchWithProject(project)
            openRunWindow()
            awaitCondition {
                app.project.run.available
                    .isNotEmpty()
            }
            val windows =
                !app.project.root.path
                    .startsWith("/")
            val wrapper = if (windows) ".\\kotlin.bat" else "./kotlin"
            assertEquals(
                listOf("$wrapper test", "$wrapper run"),
                app.project.run.available
                    .map { it.commandLine },
            )
            assertEquals(
                "All tests (Amper)",
                app.project.run.selected
                    ?.name,
            )
            assertEquals(
                RunKind.Tests,
                app.project.run.selected
                    ?.kind,
            )
            // Found, not saved: the project's preferences keep none of them.
            assertTrue(
                app.project.run.configurations
                    .isEmpty(),
            )
        }

    @Test
    fun `the scripts of every composer json of the project are offered, except the vendored ones`() =
        runComposeUiTest {
            project.file(
                "composer.json",
                """{"name": "me/app", "scripts": {"test": "phpunit", "post-install-cmd": "x"}}""",
            )
            project.file("services/api/composer.json", """{"scripts": {"lint": "phpcs", "fix style": ["phpcbf"]}}""")
            project.file("vendor/lib/composer.json", """{"scripts": {"hidden": "no"}}""")
            project.file("services/web/composer.json", "not json")
            val app = launchWithProject(project)
            openRunWindow()
            awaitCondition { app.project.run.available.size == 3 }
            val windows =
                !app.project.root.path
                    .startsWith("/")
            val composer = if (windows) "composer.bat" else "composer"
            val found =
                app.project.run.available
                    .associate { it.name to (it.commandLine to it.workingDirectory) }
            assertEquals("$composer run-script test" to "", found["Composer: test"])
            assertEquals("$composer run-script lint" to "services/api", found["Composer: lint (services/api)"])
            assertEquals(
                "$composer run-script \"fix style\"" to "services/api",
                found["Composer: fix style (services/api)"],
            )
            awaitTag(RunTags.SELECTOR).performClick()
            val lint =
                app.project.run.available
                    .first { it.name.startsWith("Composer: lint") }
            awaitTag(RunTags.configuration(lint.id)).assertIsDisplayed()
        }

    @Test
    fun `a composer script runs in the directory of its project`() =
        runComposeUiTest {
            project.file("services/api/composer.json", """{"scripts": {"lint": "phpcs"}}""")
            val app = launchWithProject(project)
            openRunWindow()
            awaitCondition {
                app.project.run.available
                    .isNotEmpty()
            }
            pressRun()
            awaitCondition { app.project.run.session != null }
            // Composer may not be installed where the test runs: either way the run ends, in the project's directory.
            awaitCondition {
                app.project.run.session
                    ?.running == false
            }
            assertEquals(
                "services/api",
                app.project.run.session
                    ?.configuration
                    ?.workingDirectory,
            )
        }
}
