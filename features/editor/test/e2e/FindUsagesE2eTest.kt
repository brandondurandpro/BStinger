package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Find usages of PHP classes, methods and functions: a click on the "N usages" hint of their declaration, Alt+F7 on
 * a use, Ctrl+click on the declaration. Each must list the usages every time, also once the hints have moved or been
 * counted again (a click used to test the hints of the first frame, and did nothing on the others).
 *
 * `src/Shop.php`: line 2 `class Cart`, line 4 `public function total(): int`, line 10 `function checkout(): int`.
 * `src/main.php`: line 1 `$cart = new Cart();`, line 2 `echo $cart->total();`, lines 3 and 4 `echo checkout();`.
 */
@OptIn(ExperimentalTestApi::class)
class FindUsagesE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            file(
                "src/Shop.php",
                listOf(
                    "<?php",
                    "",
                    "class Cart",
                    "{",
                    "    public function total(): int",
                    "    {",
                    "        return 0;",
                    "    }",
                    "}",
                    "",
                    "function checkout(): int",
                    "{",
                    "    return 1;",
                    "}",
                    "",
                ).joinToString("\n"),
            )
            file(
                "src/main.php",
                listOf(
                    "<?php",
                    "\$cart = new Cart();",
                    "echo \$cart->total();",
                    "echo checkout();",
                    "echo checkout();",
                    "",
                ).joinToString("\n"),
            )
        }
    private val servers = FakePhpServers().chosen()

    // Like intelephense: indexes the project for a while after it started, and finds no usage until it is done.
    private val indexing = FakePhpServers("--progress", "$INDEXING_SECONDS", "--blind-until-indexed").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
        indexing.delete()
    }

    private fun ComposeUiTest.open(path: String, chosen: FakePhpServers = servers): E2eApp {
        val app = launchWithProject(project, languageServers = chosen.settings)
        openFromTree(path)
        // Running, or indexing the project: the tests of the indexing server act meanwhile.
        awaitServer(app) { it.isRunning() || it.state == LanguageServerState.Indexing }
        return app
    }

    // Clicks the usage hint of [line] and waits for the list titled [title].
    private fun ComposeUiTest.clickHint(line: Int, title: String) {
        awaitTag(EditorTags.usageHint(line), timeout = SERVER_START_MS, unmerged = true)
        onNodeWithTag(EditorTags.usageHint(line), useUnmergedTree = true).performClick()
        awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
        assertEquals(title, textOf(EditorTags.LOCATIONS_TITLE))
    }

    private fun ComposeUiTest.closeList() {
        editorKeys { pressKey(Key.Escape) }
        awaitNoTag(EditorTags.LOCATIONS)
    }

    private fun ComposeUiTest.altF7At(line: Int, column: Int, title: String) {
        clickAt(line, column)
        editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
        awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
        assertEquals(title, textOf(EditorTags.LOCATIONS_TITLE))
        closeList()
    }

    @Test
    fun `a click on the usage hint of a class, a method and a function lists their usages, every time`() =
        runComposeUiTest {
            open("src/Shop.php")
            repeat(2) {
                clickHint(2, "Usages of Cart  1 usage")
                closeList()
                clickHint(4, "Usages of total  1 usage")
                closeList()
                clickHint(10, "Usages of checkout  2 usages")
                closeList()
            }
        }

    @Test
    fun `the hints still list the usages once an edit moved them`() =
        runComposeUiTest {
            val app = open("src/Shop.php")
            awaitTag(EditorTags.usageHint(10), timeout = SERVER_START_MS, unmerged = true)
            // A new line above the class: every declaration moves one line down.
            clickAt(1, 0)
            editorKeys { pressKey(Key.Enter) }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.lines
                    ?.getOrNull(3) == "class Cart"
            }
            clickHint(3, "Usages of Cart  1 usage")
            closeList()
            clickHint(5, "Usages of total  1 usage")
            closeList()
            clickHint(11, "Usages of checkout  2 usages")
            onNodeWithTag(EditorTags.location(1)).performClick()
            awaitCondition {
                app.project.editor.activeTab
                    ?.relativePath == "src/main.php"
            }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(4, 5)
            }
        }

    @Test
    fun `Alt+F7 on the use of a class, a method and a function lists their usages`() =
        runComposeUiTest {
            open("src/main.php")
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            altF7At(1, 14, "Usages of Cart  1 usage")
            altF7At(2, 13, "Usages of total  1 usage")
            altF7At(3, 7, "Usages of checkout  2 usages")
        }

    @Test
    fun `Ctrl+click on the declaration of a class, a method and a function lists their usages`() =
        runComposeUiTest {
            open("src/Shop.php")
            awaitTag(EditorTags.usageHint(10), timeout = SERVER_START_MS, unmerged = true)
            for ((at, title) in listOf(
                TextPos(2, 8) to "Usages of Cart  1 usage",
                TextPos(4, 22) to "Usages of total  1 usage",
                TextPos(10, 12) to "Usages of checkout  2 usages",
            )) {
                ctrlClickAt(at.line, at.column)
                awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
                assertEquals(title, textOf(EditorTags.LOCATIONS_TITLE))
                closeList()
            }
        }

    @Test
    fun `while the server indexes, Alt+F7 says so, then lists the usages once it is done`() =
        runComposeUiTest {
            open("src/main.php", indexing)
            clickAt(3, 7)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
            awaitCondition(SERVER_START_MS) { noticeText() == INDEXING_NOTICE }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            assertEquals("Usages of checkout  2 usages", textOf(EditorTags.LOCATIONS_TITLE))
            awaitNoTag(EditorTags.NOTICE)
        }

    @Test
    fun `the usage hints count the usages the server finds once it indexed the project`() =
        runComposeUiTest {
            open("src/Shop.php", indexing)
            awaitCondition(SERVER_START_MS) { hintText(10) == "2 usages" }
            assertEquals("1 usage", hintText(2))
            assertEquals("1 usage", hintText(4))
            clickHint(10, "Usages of checkout  2 usages")
        }

    @Test
    fun `a click on a usage hint while the server indexes lists the usages once it is done`() =
        runComposeUiTest {
            val app = open("src/Shop.php", indexing)
            awaitTag(EditorTags.usageHint(2), timeout = SERVER_START_MS, unmerged = true)
            // Ctrl+click on the declaration: the request waits for the index.
            ctrlClickAt(2, 8)
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            assertEquals("Usages of Cart  1 usage", textOf(EditorTags.LOCATIONS_TITLE))
            editorKeys { pressKey(Key.Enter) }
            awaitCondition {
                app.project.editor.activeTab
                    ?.relativePath == "src/main.php"
            }
        }

    private fun ComposeUiTest.noticeText(): String? =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(EditorTags.NOTICE),
        ).fetchSemanticsNodes().firstOrNull()?.let {
            textOf(EditorTags.NOTICE)
        }

    private fun ComposeUiTest.hintText(line: Int): String? {
        val nodes =
            onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(EditorTags.usageHint(line)),
                useUnmergedTree = true,
            )
        return nodes.fetchSemanticsNodes().firstOrNull()?.let { textOf(EditorTags.usageHint(line)) }
    }

    private companion object {
        const val INDEXING_SECONDS = 4
        const val INDEXING_NOTICE = "Indexing the project… the results will show once it is done"
    }
}
