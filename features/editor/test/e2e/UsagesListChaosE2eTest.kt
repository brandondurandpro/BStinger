package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The colors of the list of usages when the PHP grammar cannot be downloaded (network down, slow): the list opens at
 * once and works, its code colored by the line lexer, then by the grammar once it is there, without reopening it.
 * The usages come from the server, whose failures [CodeInsightChaosE2eTest] covers; the grammars are downloaded by
 * the IDE.
 */
@OptIn(ExperimentalTestApi::class)
class UsagesListChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()
    private val repository = LocalGrammarRepository()

    @AfterTest
    fun cleanup() {
        repository.close()
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.listUsages(): E2eApp {
        val app =
            launchWithProject(
                project,
                grammars = TestGrammars.store(repository),
                languageServers = servers.settings,
            )
        openFromTree("src/Greeter.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        clickAt(13, 22)
        editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
        awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `with the network down the lexer colors the usages, then the grammar once it comes back`() =
        runComposeUiTest {
            repository.down = true
            val app = listUsages()
            awaitPreviewColor(0, "\"world\"", IdeColors.StringLiteral)
            assertEquals(IdeColors.Code, previewColorOf(0, "greet"), "no grammar yet")
            // The list works meanwhile.
            onNodeWithTag(EditorTags.location(1)).performMouseInput { moveTo(center) }
            awaitCondition {
                app.project.insight.locations
                    ?.hovered == 1
            }
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.location(1)).assertIsSelected()
            repository.down = false
            awaitPreviewColor(0, "greet", IdeColors.FunctionDeclaration)
            awaitPreviewColor(1, "greet", IdeColors.FunctionDeclaration)
            onNodeWithTag(EditorTags.location(1)).assertIsSelected()
            editorKeys { pressKey(Key.Enter) }
            awaitCondition {
                app.project.editor.activeTab
                    ?.relativePath == "src/index.php"
            }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(3, 15)
            }
        }

    @Test
    fun `a slow download does not hold the list back`() =
        runComposeUiTest {
            // Down until the list is shown, then slow: the download is still going on while the list is used.
            repository.down = true
            val app = listUsages()
            repository.latencyMs = SLOW_MS
            repository.down = false
            val shown = System.nanoTime()
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.location(1)).assertIsSelected()
            assertEquals(IdeColors.StringLiteral, previewColorOf(1, "\"again\""), "colored by the lexer meanwhile")
            val usable = (System.nanoTime() - shown) / NANOS_PER_MILLI
            assert(usable < SLOW_MS) { "the list was usable after $usable ms, the download takes $SLOW_MS ms" }
            awaitPreviewColor(1, "greet", IdeColors.FunctionDeclaration, timeout = 2 * SLOW_MS + TIMEOUT)
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.LOCATIONS)
            assertEquals(null, app.project.insight.locations)
        }

    private companion object {
        const val SLOW_MS = 3_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
