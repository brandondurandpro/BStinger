package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.RunTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Run widget at the top right of the window: the commands of the project by folder, start and stop. */
@OptIn(ExperimentalTestApi::class)
class RunWidgetE2eTest {
    private val project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun composerProjects() {
        project.file("composer.json", """{"scripts": {"test": "phpunit"}}""")
        project.file("services/api/composer.json", """{"scripts": {"lint": "phpcs", "serve": "php -S x"}}""")
        project.file("services/web/composer.json", """{"scripts": {"build": "npm run build"}}""")
    }

    @Test
    fun `the widget is in the toolbar before any window is opened`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(RunTags.WIDGET).assertIsDisplayed()
            awaitTag(RunTags.WIDGET_SELECTOR).assertIsDisplayed()
            awaitNoTag(RunTags.RUN)
        }

    @Test
    fun `the menu lists the commands of the project by folder`() =
        runComposeUiTest {
            composerProjects()
            val app = launchWithProject(project)
            awaitCondition { app.project.run.available.size == 4 }
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.widgetFolder("")).assertIsDisplayed()
            awaitTag(RunTags.widgetFolder("services/api")).assertIsDisplayed()
            awaitTag(RunTags.widgetFolder("services/web")).assertIsDisplayed()
            for (configuration in app.project.run.available) {
                awaitTag(RunTags.widgetItem(configuration.id)).assertIsDisplayed()
            }
        }

    @Test
    fun `the scripts of package json files are offered, run by the manager of their lock file`() =
        runComposeUiTest {
            project.file("package.json", """{"scripts": {"dev": "vite"}}""")
            project.file("web/package.json", """{"scripts": {"build": "vite build"}}""")
            project.file("web/pnpm-lock.yaml", "")
            project.file("node_modules/dep/package.json", """{"scripts": {"install": "x"}}""")
            val app = launchWithProject(project)
            awaitCondition { app.project.run.available.size == 2 }
            val available = app.project.run.available
            val commands = available.map { it.commandLine }.toSet()
            assertEquals(setOf("npm run dev", "pnpm run build"), commands)
            val build = available.firstOrNull { it.commandLine == "pnpm run build" }
            assertNotNull(build)
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.widgetFolder("web")).assertIsDisplayed()
            awaitTag(RunTags.widgetItem(build.id)).assertIsDisplayed()
        }

    @Test
    fun `choosing a command runs it in its folder and shows the Run window`() =
        runComposeUiTest {
            composerProjects()
            val app = launchWithProject(project)
            awaitCondition { app.project.run.available.size == 4 }
            val lint =
                app.project.run.available
                    .first { it.name.startsWith("Composer: lint") }
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.widgetItem(lint.id)).performClick()
            awaitTag(RunTags.RUN)
            awaitCondition { app.project.run.session != null }
            assertEquals(
                lint.id,
                app.project.run.session
                    ?.configuration
                    ?.id,
            )
            assertEquals(
                "services/api",
                app.project.run.session
                    ?.configuration
                    ?.workingDirectory,
            )
            assertEquals(lint.id, app.project.run.selectedId)
            // The menu closed itself.
            awaitNoTag(RunTags.WIDGET_MENU)
        }

    @Test
    fun `the start button runs the selected configuration and the stop button ends it`() =
        runComposeUiTest {
            project.file("out.txt", "up\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            awaitTag(RunTags.HIDE).performClick()
            awaitNoTag(RunTags.RUN)
            onNodeWithTag(RunTags.WIDGET_START).performClick()
            awaitTag(RunTags.RUN)
            awaitCondition { app.runOutput() == listOf("up") }
            onNodeWithTag(RunTags.WIDGET_STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
        }

    @Test
    fun `without any command the widget offers to add one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(RunTags.WIDGET_SELECTOR)
            assertTrue(
                app.project.run.available
                    .isEmpty(),
            )
            onNodeWithTag(RunTags.WIDGET_START).performClick()
            awaitNoTag(RunTags.RUN)
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.WIDGET_NEW).performClick()
            awaitTag(RunTags.EDITOR).assertIsDisplayed()
        }

    @Test
    fun `the selected configuration can be edited from the menu`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Mine", "some-command")
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.WIDGET_EDIT).performClick()
            awaitTag(RunTags.EDITOR).assertIsDisplayed()
            assertEquals(
                "Mine",
                app.project.run.editing
                    ?.name,
            )
        }

    @Test
    fun `the search field filters the commands and the recent ones come first`() =
        runComposeUiTest {
            composerProjects()
            val app = launchWithProject(project)
            awaitCondition { app.project.run.available.size == 4 }
            val available = app.project.run.available
            val lint = available.firstOrNull { it.name.startsWith("Composer: lint") }
            assertNotNull(lint)
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.WIDGET_SEARCH).performTextInput("lint")
            awaitTag(RunTags.widgetItem(lint.id)).assertIsDisplayed()
            awaitNoTag(RunTags.widgetFolder("services/web"))
            awaitTag(RunTags.WIDGET_SEARCH).performTextReplacement("zzz")
            awaitTag(RunTags.WIDGET_NO_MATCH).assertIsDisplayed()
            awaitTag(RunTags.WIDGET_SEARCH).performTextReplacement("lint")
            awaitTag(RunTags.widgetItem(lint.id)).performClick()
            val recentIds = app.project.run.recentIds
            awaitCondition { recentIds.toList() == listOf(lint.id) }
            onNodeWithTag(RunTags.WIDGET_SELECTOR).performClick()
            awaitTag(RunTags.widgetRecent(lint.id)).assertIsDisplayed()
            awaitTag(RunTags.widgetRecentFolder("services/api")).assertIsDisplayed()
        }

    @Test
    fun `the widget shows that a command is running until it is stopped`() =
        runComposeUiTest {
            project.file("out.txt", "up\n")
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            awaitTag(RunTags.HIDE).performClick()
            awaitNoTag(RunTags.WIDGET_STATUS)
            onNodeWithTag(RunTags.WIDGET_START).performClick()
            awaitTag(RunTags.WIDGET_STATUS).assertIsDisplayed()
            onNodeWithTag(RunTags.WIDGET_STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
            awaitNoTag(RunTags.WIDGET_STATUS)
        }
}
