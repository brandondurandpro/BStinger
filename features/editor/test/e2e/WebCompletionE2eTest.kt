package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Completion in a file two servers serve: the TypeScript server and the Tailwind CSS one each propose what they know,
 * in one list (see #98).
 */
@OptIn(ExperimentalTestApi::class)
class WebCompletionE2eTest {
    private val project = FakePhpServers.tailwindProject()
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.document(): Document {
        val tab = project.editor.activeTab
        return checkNotNull(tab?.document)
    }

    private fun ComposeUiTest.openView(app: E2eApp) {
        openFromTree("src/view.tsx")
        awaitCondition(SERVER_START_MS) { app.languageServers.count { it.isRunning() } == 2 }
        onNodeWithTag(EditorTags.EDITOR).performClick()
        caretToEnd()
    }

    @Test
    fun `the server of the language and the companion server both propose, in one list`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openView(app)
            typeCode("x = 'tw")
            awaitCompletion("twflex")
            awaitCompletion("twgrid")
            editorKeys { pressKey(Key.Enter) }
            awaitNoTag(EditorTags.COMPLETION)
            assertEquals("x = 'twflex", app.document().line(1))
        }

    @Test
    fun `what only the server of the language knows is still proposed`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openView(app)
            typeCode("strto")
            awaitCompletion("strtoupper")
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.COMPLETION)
        }

    @Test
    fun `a companion server that never answers does not hold back the code completion`() =
        runComposeUiTest {
            val stuck = FakePhpServers(tailwindOptions = arrayOf("--hang-completion"))
            try {
                val app = launchWithProject(project, languageServers = stuck.settings)
                openView(app)
                typeCode("x = 'strto")
                measure("completion with a stuck companion server", budgetMillis = 3_000, budgetMegabytes = 200) {
                    awaitCompletion("strtoupper")
                }
            } finally {
                stuck.delete()
            }
        }

    @Test
    fun `the Tailwind CSS server is not asked outside a string`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openView(app)
            typeCode("tw")
            awaitNoTag(EditorTags.COMPLETION)
        }

    @Test
    fun `typing a dot after a name proposes the members at once`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openView(app)
            typeCode("date.")
            awaitCompletion("strtoupper")
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.COMPLETION)
        }

    @Test
    fun `a dot after a number is a decimal point and proposes nothing`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openView(app)
            typeCode("x = 1.")
            awaitNoTag(EditorTags.COMPLETION)
        }
}
