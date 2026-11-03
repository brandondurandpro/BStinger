package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FUNCTIONS = 300
private const val UNKNOWN_CALLS = 1_000
private const val COMMON_CALLS = 1_500
private const val SCROLL_FRAMES = 150
private const val ROWS_PER_FRAME = 10
private const val TYPED = "abcdefghij"

/**
 * Cost of code intelligence on a large PHP file: a thousand problems, hundreds of declarations with their usage
 * counts, a symbol used 1,500 times.
 */
@OptIn(ExperimentalTestApi::class)
class CodeInsightPerformanceE2eTest {
    private val declarations = (1..FUNCTIONS).joinToString("\n") { "function f$it(int \$a): int { return \$a; }" }
    private val calls = (1..FUNCTIONS).joinToString("\n") { "f$it($it);" }
    private val unknown = (1..UNKNOWN_CALLS).joinToString("\n") { "missing$it();" }
    private val common = (1..COMMON_CALLS).joinToString("\n") { "common();" }
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val parts = listOf("<?php", "function common() {}", declarations, calls, unknown, common, "")
            file("src/big.php", parts.joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.tab(): EditorTab = project.editor.activeTab!!

    private fun ComposeUiTest.openBig(): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/big.php")
        awaitServer(app) { it.isRunning() }
        onNodeWithTag(EditorTags.EDITOR).performClick()
        return app
    }

    private fun ComposeUiTest.awaitAllProblems() =
        awaitCondition(SERVER_START_MS) { textOf(EditorTags.PROBLEMS_ERRORS) == "$UNKNOWN_CALLS" }

    @Test
    fun `a thousand problems and the usage counts of hundreds of declarations arrive quickly`() =
        runComposeUiTest {
            val app = openBig()
            measure("1,000 problems of a 3,000-line file shown", budgetMillis = 20_000) { awaitAllProblems() }
            val insight = app.project.insight.insightOf(app.tab().document!!)!!
            measure("usage counts of a 300-declaration file", budgetMillis = 20_000) {
                awaitCondition(SERVER_START_MS) { insight.usageCounts.isNotEmpty() }
            }
            // At most the first declarations are counted: a request per symbol, not per symbol of a huge file.
            assertTrue(insight.usageCounts.size <= 150, "${insight.usageCounts.size} counted")
            assertEquals(COMMON_CALLS, insight.usageCounts.firstOrNull { it.name == "common" }?.count)
        }

    @Test
    fun `typing above a thousand problems keeps them in place`() =
        runComposeUiTest {
            val app = openBig()
            awaitAllProblems()
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            editorKeys { pressKey(Key.DirectionDown) }
            // On a new empty line before `function common() {}`.
            typeCode("\n")
            editorKeys { pressKey(Key.DirectionUp) }
            measure("type ${TYPED.length} chars above 1,000 problems", budgetMillis = 6_000) {
                for (c in TYPED) {
                    typeCode("$c")
                    renderFrame()
                }
            }
            val insight = app.project.insight.insightOf(app.tab().document!!)!!
            assertEquals(UNKNOWN_CALLS, insight.diagnostics.size)
        }

    @Test
    fun `scrolling a file full of problems and usage hints`() =
        runComposeUiTest {
            val app = openBig()
            awaitAllProblems()
            val scroll = app.tab().verticalScroll

            fun scrollFrames(moving: Boolean) =
                costOf {
                    runBlocking { scroll.scrollToRow(0) }
                    renderFrame()
                    val held = CoroutineScope(Dispatchers.Unconfined)
                    held.launch { scroll.scrollableState.scroll { awaitCancellation() } }
                    repeat(SCROLL_FRAMES) { frame ->
                        if (moving) runBlocking { scroll.scrollToRow(frame * ROWS_PER_FRAME) }
                        renderFrame()
                    }
                    held.cancel()
                }
            scrollFrames(moving = true)
            val still = scrollFrames(moving = false)
            report(
                "scroll 150 frames of 10 rows with problems (net)",
                scrollFrames(moving = true) - still,
                budgetMillis = 3_000,
                budgetMegabytes = 400,
            )
        }

    @Test
    fun `the usages of a symbol used 1,500 times open in a virtualized list`() =
        runComposeUiTest {
            openBig()
            awaitAllProblems()
            // `function common() {}`, line 1.
            clickAt(1, 11)
            measure("list 1,500 usages", budgetMillis = 5_000) {
                editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
                awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            }
            val title = textOf(EditorTags.LOCATIONS_TITLE)
            assertTrue(title.startsWith("Usages of common"), title)
            val composed = onAllNodes(hasTestTagPrefix("locations.item:")).fetchSemanticsNodes().size
            assertTrue(composed <= 16, "$composed rows composed")
            measure("move the selection 100 rows", budgetMillis = 3_000) {
                repeat(100) {
                    editorKeys { pressKey(Key.DirectionDown) }
                    renderFrame()
                }
            }
        }

    @Test
    fun `the code of 1,500 usages is colored by the grammar, and the mouse moves over the rows`() =
        runComposeUiTest {
            val app = openBig()
            awaitAllProblems()
            clickAt(1, 11)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            measure("color the code of 1,500 usages", budgetMillis = 3_000, budgetMegabytes = 300) {
                awaitCondition {
                    app.project.insight.locations
                        ?.previewColors != null
                }
                renderFrame()
            }
            assertEquals(IdeColors.FunctionDeclaration, previewColorOf(0, "common"))
            measure("hover 12 rows 10 times", budgetMillis = 3_000, budgetMegabytes = 300) {
                repeat(10) {
                    for (row in 0 until 12) {
                        onNodeWithTag(EditorTags.location(row)).performMouseInput { moveTo(center) }
                        renderFrame()
                    }
                }
            }
            assertEquals(
                11,
                app.project.insight.locations
                    ?.hovered,
            )
        }

    @Test
    fun `the documentation of a function of a large file shows quickly`() =
        runComposeUiTest {
            openBig()
            awaitAllProblems()
            // A first hover warms the server up.
            clickAt(2, 10)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Q) } }
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Escape) }
            clickAt(3, 10)
            measure("documentation in a 3,000-line file", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Q) } }
                awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            }
        }
}
