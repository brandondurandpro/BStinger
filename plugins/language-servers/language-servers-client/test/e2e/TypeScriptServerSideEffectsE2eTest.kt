package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The servers of the web start processes in the project: opening its files, with the TypeScript, Vue and Tailwind CSS
 * servers running, changes nothing in it (no file, no cache, no link, nothing in Git).
 */
@OptIn(ExperimentalTestApi::class)
class TypeScriptServerSideEffectsE2eTest {
    private val project = FakePhpServers.tailwindProject().apply { commitAll() }
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `opening files of the web with their servers running leaves the project as it was`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, languageServers = servers.settings)
            for (file in listOf("src/app.ts", "src/view.tsx", "src/legacy.js", "src/App.vue")) openFromTree(file)
            awaitCondition(SERVER_START_MS) { app.languageServers.count { it.isRunning() } == 3 }
            assertEquals(3, app.languageServers.size)
            before.assertUnchanged(project)
        }
}
