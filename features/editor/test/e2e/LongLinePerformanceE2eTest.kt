package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import org.example.kotlineditor.ui.MAX_COLORED_CHARACTERS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LONG_LINE_CHARACTERS = 1_000_000
private const val SCROLL_STEPS = 100
private const val TYPED = 20
private const val CHUNKS = 12
private const val CHUNK_CHARACTERS = 300_000
private const val PATH = "dist/bundle.js"

/**
 * A minified file, through the real UI: a bundle whose code is one line of a million characters, in the editor
 * and in the diff of its change. Its line is wider than a layout can be (the editor threw `Can't represent a
 * width of 775277`), and laid out whole in a row of the diff it took gigabytes, until the IDE died.
 */
@OptIn(ExperimentalTestApi::class)
class LongLinePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun bundle(): String {
        val statement = "function f(a,b){return a+b*2}var s='text';"
        val line = statement.repeat(LONG_LINE_CHARACTERS / statement.length)
        return listOf("/* bundle */", line, "//# sourceMappingURL=bundle.js.map", "").joinToString("\n")
    }

    private fun ComposeUiTest.shownText(line: Int): AnnotatedString {
        val node = onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].single()
    }

    @Test
    fun `a file of one very long line opens, scrolls sideways and is edited`() =
        runComposeUiTest {
            project.file(PATH, bundle())
            val app = launchWithProject(project)
            measure("open a file with a line of $LONG_LINE_CHARACTERS characters", 10_000, 600) {
                openFromTree(PATH)
                awaitTag(EditorTags.line(1))
                renderFrame()
            }
            val tab = checkNotNull(app.project.editor.activeTab)
            val document = checkNotNull(tab.document)
            // The start of the line has its colors; beyond, a line this long keeps the color of the text.
            val styles = shownText(line = 1).spanStyles
            assertTrue(styles.isNotEmpty(), "the start of the long line is not colored")
            assertTrue(styles.all { it.end <= MAX_COLORED_CHARACTERS }, "the long line is colored whole")
            val scroll = tab.horizontalScroll
            awaitCondition { scroll.maxOffset > 0f }
            // To the end of the line, a screen and more at a time: only the slices on screen are laid out.
            measure("scroll $SCROLL_STEPS steps along the long line", 10_000, 600) {
                repeat(SCROLL_STEPS) { step ->
                    runOnIdle { scroll.scrollTo(scroll.maxOffset * (step + 1) / SCROLL_STEPS) }
                    renderFrame()
                }
            }
            assertEquals(scroll.maxOffset, scroll.offset)
            // Each character typed changes the line: only its start is colored again, and the slices on screen
            // laid out (colored whole, it took 100 ms and 130 MB a character).
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.DirectionDown)
            }
            awaitCondition { document.caret == TextPos(1, 0) }
            measure("type $TYPED characters in the long line", 5_000, 1_000) {
                typeCode("x".repeat(TYPED))
                renderFrame()
            }
            assertTrue(document.lines[1].startsWith("x".repeat(TYPED) + "function"))
        }

    // A bundle as a build makes them: short lines, and a dozen minified ones full of the same short names.
    private fun chunkedBundle(): String {
        val statement = "function f(a,b){return a+b*2}var s='text',n=f(1,2);"
        val minified = statement.repeat(CHUNK_CHARACTERS / statement.length)
        val chunks = (0 until CHUNKS).flatMap { listOf("// chunk $it", minified, "f($it)") }
        return (listOf("f(0)") + chunks + "").joinToString("\n")
    }

    /**
     * Scrolling froze on a real bundle (a quarter of a second a frame): the cell of each occurrence of the name
     * at the caret, thousands on each minified line, was counted from the start of its line, in each frame.
     */
    @Test
    fun `a bundle of minified lines scrolls`() =
        runComposeUiTest {
            project.file(PATH, chunkedBundle())
            val app = launchWithProject(project)
            openFromTree(PATH)
            awaitTag(EditorTags.line(1))
            val tab = checkNotNull(app.project.editor.activeTab)
            val rows = checkNotNull(tab.document).lineCount
            // The caret is on `f`, whose occurrences are marked on every line.
            awaitCondition { checkNotNull(tab.document).caret == TextPos(0, 0) }
            measure("scroll through a bundle of $CHUNKS minified lines", 5_000, 600) {
                for (row in 0 until rows) {
                    runBlocking { tab.verticalScroll.scrollToRow(row) }
                    renderFrame()
                }
            }
        }

    @Test
    fun `the diff of a file of one very long line opens`() =
        runComposeUiTest {
            project.commitAll()
            project.file(PATH, bundle())
            launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            val change = awaitTag(GitTags.gitChange(PATH))
            val usedBefore = NativeMemory.inUseMegabytes()
            measure("open the diff of a line of $LONG_LINE_CHARACTERS characters", 10_000, 600) {
                change.performClick()
                awaitTag(EditorTags.diffLine(2))
                renderFrame()
            }
            if (usedBefore != null) {
                val used = checkNotNull(NativeMemory.inUseMegabytes()) - usedBefore
                println("PERF ${"native memory of the diff of a long line".padEnd(50)} $used MB")
            }
        }
}
