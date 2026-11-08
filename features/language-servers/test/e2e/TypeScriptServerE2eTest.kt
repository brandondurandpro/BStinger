package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.e2e.FakePhpServers.Companion.FAKE_TYPESCRIPT
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The TypeScript, JavaScript and React server through the UI: the only one of its language, so it just starts. */
@OptIn(ExperimentalTestApi::class)
class TypeScriptServerE2eTest {
    private val project = FakePhpServers.webProject()
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `a TypeScript file starts its server without asking, and nothing is saved`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("README.md")
            awaitNoTag(LanguageServerTags.LSP_CHOICE, timeout = 1_000)
            assertTrue(app.languageServers.isEmpty(), "a Markdown file starts no server")

            openFromTree("src/app.ts")
            val running = awaitServer(app) { it.state == LanguageServerState.Running && it.memory != null }
            assertEquals(FAKE_TYPESCRIPT, running.serverName)
            assertEquals("typescript", running.language)
            assertEquals(project.path, running.root)
            awaitNoTag(LanguageServerTags.LSP_CHOICE)
            assertTrue(isAlive(running.pid!!))
            awaitCondition {
                textOf(LanguageServerTags.statusLanguageServer(running.id))
                    .matches(Regex("TypeScript and JavaScript · \\d+ MB"))
            }
            assertEquals("", servers.saved(), "no choice was made, so none is saved")
        }

    @Test
    fun `TypeScript, React, Vue and JavaScript files share one server process`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            openFromTree("src/view.tsx")
            openFromTree("src/legacy.js")
            openFromTree("src/App.vue")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitNoTag(LanguageServerTags.LSP_CHOICE)
            assertEquals(1, app.languageServers.size)
            assertEquals(1, running.users)
        }

    @Test
    fun `the server is stopped and started again from the Language Servers window`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/view.tsx")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(LanguageServerTags.statusLanguageServer(running.id)).performClick()
            awaitTag(LanguageServerTags.languageServerStop(running.id)).performClick()
            awaitServer(app) { it.state == LanguageServerState.Stopped }
            val status = LanguageServerTags.statusLanguageServer(running.id)
            awaitCondition { textOf(status) == "TypeScript and JavaScript · stopped" }
            awaitCondition { !isAlive(running.pid!!) }
            onNodeWithTag(LanguageServerTags.languageServerStart(running.id)).performClick()
            awaitServer(app) { it.state == LanguageServerState.Running && it.pid != running.pid }
        }

    @Test
    fun `a TypeScript server that crashes is shown crashed with its error`() =
        runComposeUiTest {
            val crashing = FakePhpServers("--crash-on-start")
            try {
                val app = launchWithProject(project, languageServers = crashing.settings)
                openFromTree("src/app.ts")
                val crashed = awaitServer(app) { it.state == LanguageServerState.Crashed }
                awaitTag(LanguageServerTags.statusLanguageServer(crashed.id)).performClick()
                awaitCondition { textOf(LanguageServerTags.languageServerMessage(crashed.id)).contains("fake crash") }
            } finally {
                crashing.delete()
            }
        }

    @Test
    fun `a project that uses Tailwind CSS gets its server beside the TypeScript one, the others do not`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/view.tsx")
            awaitServer(app) { it.isRunning() }
            Thread.sleep(1_000)
            waitForIdle()
            assertEquals(listOf(FAKE_TYPESCRIPT), app.languageServers.map { it.serverName })
        }

    @Test
    fun `the Tailwind CSS server runs beside the TypeScript one on the files of a Tailwind project`() =
        runComposeUiTest {
            val tailwind = FakePhpServers.tailwindProject()
            try {
                val app = launchWithProject(tailwind, languageServers = servers.settings)
                openFromTree("src/view.tsx")
                awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.FAKE_TAILWIND }
                awaitServer(app) { it.isRunning() && it.serverName == FAKE_TYPESCRIPT }
                awaitNoTag(LanguageServerTags.LSP_CHOICE)
                val names = app.languageServers.map { it.languageName }.sorted()
                assertEquals(listOf("Tailwind CSS", "TypeScript and JavaScript"), names)
                assertEquals("", servers.saved(), "no choice was made")
            } finally {
                tailwind.delete()
            }
        }
}
