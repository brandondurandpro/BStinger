package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** A PHP file with a run of `use` lines and a function: the editor has no regions of its own for PHP. */
internal val IMPORTS_PHP =
    """
    <?php

    namespace App;

    use App\Alpha;
    use App\Beta;
    use App\Gamma;

    function main()
    {
        return 1;
    }
    """.trimIndent() + "\n"

internal const val FIRST_USE = 4
internal const val LAST_USE = 6
internal const val FUNCTION_LINE = 8

/** Code folding in PHP with the regions of the language server (textDocument/foldingRange). */
@OptIn(ExperimentalTestApi::class)
class PhpFoldingE2eTest {
    private val project = codeInsightProject().apply { file("src/Imports.php", IMPORTS_PHP) }
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/Imports.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `the use lines fold and unfold from the marker the language server adds`() =
        runComposeUiTest {
            open()
            awaitTag(EditorTags.foldMarker(FIRST_USE), unmerged = true, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.line(FIRST_USE + 1))
            awaitNoTag(EditorTags.line(LAST_USE))
            onNodeWithTag(EditorTags.line(FIRST_USE)).assertTextContains("use App\\Alpha;", substring = true)
            onNodeWithTag(EditorTags.line(FUNCTION_LINE)).assertExists()
            onNodeWithTag(EditorTags.foldMarker(FIRST_USE), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.line(LAST_USE)).assertTextContains("use App\\Gamma;", substring = true)
        }

    @Test
    fun `a function folds from its declaration line, which stays shown with its body hidden`() =
        runComposeUiTest {
            open()
            awaitTag(EditorTags.foldMarker(FUNCTION_LINE), unmerged = true, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.line(FUNCTION_LINE + 1))
            awaitNoTag(EditorTags.line(FUNCTION_LINE + 2))
            onNodeWithTag(EditorTags.line(FUNCTION_LINE)).assertTextContains("function main()", substring = true)
            // One marker per region: none on the brace line.
            onNodeWithTag(EditorTags.foldMarker(FUNCTION_LINE), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.line(FUNCTION_LINE + 2)).assertTextContains("return 1;", substring = true)
            awaitNoTag(EditorTags.foldMarker(FUNCTION_LINE + 1), unmerged = true)
        }

    @Test
    fun `without folding ranges from the server nothing folds, and nothing pretends to`() =
        runComposeUiTest {
            open("--without", "foldingRange")
            // The folding ranges are asked right after the usage counts: once they are shown, nothing more is coming.
            awaitTag(EditorTags.usageHint(FUNCTION_LINE), unmerged = true, timeout = SERVER_START_MS)
            awaitNoTag(EditorTags.foldMarker(FIRST_USE), unmerged = true)
            awaitNoTag(EditorTags.foldMarker(FUNCTION_LINE), unmerged = true)
        }

    @Test
    fun `after a line is inserted above, the marker follows the use lines`() =
        runComposeUiTest {
            open()
            awaitTag(EditorTags.foldMarker(FIRST_USE), unmerged = true, timeout = SERVER_START_MS)
            clickAt(2, 0)
            editorKeys { pressKey(Key.Enter) }
            awaitTag(EditorTags.foldMarker(FIRST_USE + 1), unmerged = true, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.line(LAST_USE + 1))
            onNodeWithTag(EditorTags.line(FUNCTION_LINE + 1)).assertExists()
        }
}
