package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.LanguageServerState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val FILES = 3_000

/** Cost of the TypeScript server for the IDE in a project of thousands of files. */
@OptIn(ExperimentalTestApi::class)
class TypeScriptServerPerformanceE2eTest {
    private val servers = FakePhpServers()
    private val project =
        FakePhpServers.webProject().apply {
            repeat(FILES) { file("src/generated/module$it.ts", "export const value$it = $it\n") }
        }

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `opening a TypeScript file of a project of thousands of files starts its server quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            val label = "open a TypeScript file and start its server in $FILES files"
            measure(label, budgetMillis = 10_000, budgetMegabytes = 200) {
                openFromTree("src/app.ts")
                awaitServer(app) { it.state == LanguageServerState.Running && it.memory != null }
                renderFrame()
            }
        }

    @Test
    fun `switching between TypeScript, React and JavaScript files keeps one process and costs little`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            awaitServer(app) { it.state == LanguageServerState.Running }
            measure("open a tsx and a js file with the server running", budgetMillis = 2_000, budgetMegabytes = 100) {
                openFromTree("src/view.tsx")
                openFromTree("src/legacy.js")
                renderFrame()
            }
            assertEquals(1, app.languageServers.size)
        }
}
