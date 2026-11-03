package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.FinishedSpan
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TerminalTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anonymous performance measures, through the real UI and server: asked once, nothing measured without the
 * user's yes, each feature measured from the user's action to the frame that shows its result, the server's work
 * a child of the UI's, and never a path, a name or a content in what would leave the machine.
 */
@OptIn(ExperimentalTestApi::class)
class TelemetryE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.awaitSpan(capture: TelemetryCapture, operation: Operation): FinishedSpan {
        awaitCondition { capture.clientSpansOf(operation).isNotEmpty() }
        return checkNotNull(capture.clientSpansOf(operation).lastOrNull())
    }

    private fun ComposeUiTest.awaitServerSpans(capture: TelemetryCapture) =
        awaitCondition { capture.serverSpans.any { it.operation == Operation.RpcServer } }

    @Test
    fun `the first launch asks without blocking, and nothing is measured before the user agrees`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val preferences =
                org.example.kotlineditor.state
                    .InMemoryPreferenceStore()
            launchApp(preferences = preferences, telemetry = capture)
            awaitTag(AppTags.TELEMETRY_BANNER)
            // The banner does not stand in the way: the project opens under it.
            openProjectDirectoryFromWelcome(project.path)
            awaitTag(TreeTags.treeRow("src"))
            openFromTree("README.md")
            assertEquals(emptyList(), capture.all)
            assertTrue(PreferenceKeys.TELEMETRY_ENABLED !in preferences.load())
        }

    @Test
    fun `agreeing measures at once on both sides and is remembered with a random installation id`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val preferences =
                org.example.kotlineditor.state
                    .InMemoryPreferenceStore()
            launchApp(preferences = preferences, telemetry = capture)
            awaitTag(AppTags.TELEMETRY_DETAILS).performClick()
            awaitTag(AppTags.TELEMETRY_SENT)
            onNodeWithTag(AppTags.TELEMETRY_ACCEPT).performClick()
            awaitNoTag(AppTags.TELEMETRY_BANNER)
            openProjectDirectoryFromWelcome(project.path)
            awaitSpan(capture, Operation.ProjectOpen)
            awaitServerSpans(capture)
            awaitCondition { preferences.load()[PreferenceKeys.TELEMETRY_ENABLED] == "true" }
            val instance = preferences.load()[PreferenceKeys.TELEMETRY_INSTANCE].orEmpty()
            assertTrue(Regex("[0-9a-f-]{36}").matches(instance), instance)
        }

    @Test
    fun `refusing measures nothing on either side, and the question is not asked again`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val preferences =
                org.example.kotlineditor.state
                    .InMemoryPreferenceStore()
            launchApp(preferences = preferences, telemetry = capture)
            awaitTag(AppTags.TELEMETRY_DECLINE).performClick()
            awaitNoTag(AppTags.TELEMETRY_BANNER)
            openProjectDirectoryFromWelcome(project.path)
            awaitTag(TreeTags.treeRow("src"))
            openFromTree("README.md")
            awaitCondition { preferences.load()[PreferenceKeys.TELEMETRY_ENABLED] == "false" }
            assertEquals(emptyList(), capture.all)
            assertTrue(PreferenceKeys.TELEMETRY_INSTANCE !in preferences.load())
        }

    @Test
    fun `a file opened is measured to its frame, and the server's read is a child of the UI's request`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            capture.clear()
            openFromTree("src/main/kotlin/App.kt")
            val opened = awaitSpan(capture, Operation.EditorOpenFile)
            assertEquals(Outcome.Ok, opened.outcome)
            assertEquals("kotlin", opened.attributes[Attribute.Language])
            assertEquals("<1k", opened.attributes[Attribute.Size])
            val requests = capture.clientSpansOf(Operation.RpcClient)
            val read = requests.lastOrNull { it.attributes[Attribute.Method] == "fs/read" }
            checkNotNull(read) { "No fs/read among ${capture.clientSpans.map { it.attributes[Attribute.Method] }}" }
            assertEquals(opened.context.spanId, read.parent?.spanId)
            assertEquals(opened.context.traceId, read.context.traceId)
            awaitCondition { capture.serverSpans.any { it.parent?.spanId == read.context.spanId } }
            val served = capture.serverSpans.lastOrNull { it.parent?.spanId == read.context.spanId }
            assertEquals("fs/read", served?.attributes?.get(Attribute.Method))
        }

    @Test
    fun `each feature is measured from the user's action to the frame that shows its result`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            awaitSpan(capture, Operation.ProjectOpen)
            awaitSpan(capture, Operation.TreeExpand)
            openFromTree("README.md")
            awaitSpan(capture, Operation.EditorOpenFile)
            // Save.
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.X) }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            awaitSpan(capture, Operation.EditorSave)
            // Find in the file.
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("Demo")
            assertEquals("<1k", awaitSpan(capture, Operation.EditorFind).attributes[Attribute.Size])
            // Find in Files.
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("guide")
            awaitCondition {
                val searches = capture.clientSpansOf(Operation.SearchFindInFiles)
                searches.any { it.attributes[Attribute.Result] == "found" }
            }
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            // The terminal.
            onNodeWithTag(AppTags.TOOL_TERMINAL).performClick()
            awaitTag(TerminalTags.TERMINAL_INPUT)
            awaitSpan(capture, Operation.TerminalStart)
        }

    @Test
    fun `no measure carries a path, a file name, a content or a query`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            project.file("src/SecretName.kt", "val secretContent = 1\n")
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            openFromTree("src/SecretName.kt")
            awaitSpan(capture, Operation.EditorOpenFile)
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("secretQuery")
            awaitSpan(capture, Operation.SearchFindInFiles)
            awaitServerSpans(capture)
            val values = capture.all.flatMap { span -> Attribute.entries.mapNotNull { span.attributes[it] } }
            val errors = capture.all.mapNotNull { (it.outcome as? Outcome.Error)?.type }
            val secrets = listOf(project.path, "SecretName", "secretContent", "secretQuery", "src")
            for (value in (values + errors).toSet()) {
                assertTrue(secrets.none { it in value }, "'$value' leaked")
            }
        }

    @Test
    fun `switched off from the menu, nothing more is measured, on either side`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val preferences = TelemetryCapture.agreed()
            val app = launchWithProject(project, preferences = preferences, telemetry = capture)
            awaitServerSpans(capture)
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_TELEMETRY).performClick()
            awaitCondition { !app.server.telemetry.telemetry.isEnabled }
            awaitCondition { preferences.load()[PreferenceKeys.TELEMETRY_ENABLED] == "false" }
            capture.clear()
            openFromTree("README.md")
            awaitEditorReady()
            assertEquals(emptyList(), capture.all)
            // And back on, at once.
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_TELEMETRY).performClick()
            openFromTree("docs/guide.md")
            awaitSpan(capture, Operation.EditorOpenFile)
            awaitServerSpans(capture)
        }
}
