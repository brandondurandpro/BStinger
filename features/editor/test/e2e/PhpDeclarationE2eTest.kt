package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ctrl+click and Ctrl+B with a server that answers textDocument/declaration but not textDocument/definition. */
@OptIn(ExperimentalTestApi::class)
class PhpDeclarationE2eTest {
    private val project = codeInsightProject()
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun E2eApp.activePath(): String? = project.editor.activeTab?.relativePath

    @Test
    fun `Ctrl+click goes to the declaration when the server has no definitions`() =
        runComposeUiTest {
            val app = open("--without", "definition")
            ctrlClickAt(2, 17)
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(13, 20)
            }
        }

    @Test
    fun `Ctrl+B says it cannot go anywhere when nothing declares the word`() =
        runComposeUiTest {
            // Neither the server (no definitions, no declarations) nor the IDE's index of this project knows it.
            val app = open("--without", "definition,declaration")
            clickAt(2, 17)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("Cannot find declaration to go to", textOf(EditorTags.NOTICE))
            assertEquals("src/index.php", app.activePath())
        }
}
