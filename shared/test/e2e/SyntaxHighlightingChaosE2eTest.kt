package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the user sees when the grammar of a file cannot be downloaded: the network is down, slow, or answers a page
 * instead of the jar (a captive portal). The file opens and is edited at once, colored by the line lexer, and the
 * grammar's colors come once the download succeeds, without reopening it. Grammars are downloaded by the IDE, not by
 * its server: a server failure does not reach them (see [ServerConnectionE2eTest] for the server's link).
 *
 * In `App.kt`, line 7 is `val greeting = "Hello"` (the lexer colors `val`) and line 8 `println(greeting.shout())`
 * (only the grammar colors `println`).
 */
@OptIn(ExperimentalTestApi::class)
class SyntaxHighlightingChaosE2eTest {
    private val project = SampleProject.standard()
    private val repository = LocalGrammarRepository()

    @AfterTest
    fun cleanup() {
        repository.close()
        project.delete()
    }

    @Test
    fun `with the network down the lexer colors the file, then the grammar once it comes back`() =
        runComposeUiTest {
            repository.down = true
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(7, "val", IdeColors.Keyword)
            assertEquals(IdeColors.Code, colorOf(8, "println"), "no grammar yet")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            pressInEditor(Key.MoveEnd, ctrl = true)
            typeInEditor("\nval more = greeting.length")
            assertEquals("val more = greeting.length", textOfLine(10))
            repository.down = false
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            awaitColor(10, "length", IdeColors.Property)
            assertEquals(1, repository.jarsServed)
        }

    @Test
    fun `a slow download does not hold the file back`() =
        runComposeUiTest {
            repository.latencyMs = SLOW_MS
            launchWithProject(project, grammars = TestGrammars.store(repository))
            val opened = System.nanoTime()
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            pressInEditor(Key.MoveEnd, ctrl = true)
            typeInEditor("\nval more = greeting.length")
            val ready = (System.nanoTime() - opened) / NANOS_PER_MILLI
            assert(ready < SLOW_MS) { "the file was usable after $ready ms, the download takes $SLOW_MS ms" }
            assertEquals(IdeColors.Code, colorOf(10, "length"), "colored by the lexer while downloading")
            awaitColor(10, "length", IdeColors.Property, timeout = SLOW_MS + TIMEOUT)
            assertEquals(IdeColors.FunctionDeclaration, colorOf(8, "println"))
        }

    @Test
    fun `a page answered instead of the grammar is never loaded`() =
        runComposeUiTest {
            repository.captivePortal = true
            val store = TestGrammars.store(repository)
            launchWithProject(project, grammars = store)
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(7, "val", IdeColors.Keyword)
            // Several attempts are answered the page: none is loaded, the lexer keeps coloring.
            awaitCondition { store.downloads >= 3 }
            assertEquals(IdeColors.Code, colorOf(8, "println"))
            repository.captivePortal = false
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
        }

    @Test
    fun `the editor server restarting does not lose the colors`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            app.process.stop()
            awaitReconnecting()
            app.process.start()
            awaitReconnected()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            pressInEditor(Key.MoveEnd, ctrl = true)
            typeInEditor("\nval more = greeting.length")
            awaitColor(10, "length", IdeColors.Property)
            assertEquals(1, repository.jarsServed)
        }

    private fun ComposeUiTest.textOfLine(line: Int): String? =
        onAllNodes(hasTestTag(EditorTags.lineText(line)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrElseNullable(SemanticsProperties.Text) { null }
            ?.firstOrNull()
            ?.text

    private companion object {
        const val SLOW_MS = 3_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
