package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.RunTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** What the Run tool window must not change in the project: it runs the user's commands, and keeps nothing there. */
@OptIn(ExperimentalTestApi::class)
class RunSideEffectsE2eTest {
    private var project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `configurations, runs, stops and the files opened from the output leave the project as it was`() =
        runComposeUiTest {
            val file = project.root.resolve("src/main/kotlin/App.kt")
            project.file(
                "out.txt",
                textOf("e: ${file.toUri()}:8:5 broken", "Started T", "Started ok()", "Passed ok()", ""),
            )
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Program", emit("out.txt", exitCode = 1))
            addConfiguration("Tests", emit("out.txt"), RunKind.Tests)
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            pressRun()
            awaitCondition { app.runOutput().isNotEmpty() }
            onNodeWithTag(RunTags.STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
            val program =
                app.project.run.configurations
                    .first { it.name == "Program" }
            app.project.run.run(program)
            awaitRunStatus(app, RunStatus.Failed)
            awaitOutputLink(app, 0, 0).performClick()
            awaitTag(EditorTags.tab("src/main/kotlin/App.kt"))
            awaitEditorReady()
            before.assertUnchanged(project)
        }

    @Test
    fun `a project with unusual files is left as it was too`() =
        runComposeUiTest {
            project.delete()
            project = SampleProject.withUnusualFiles().withEmitProgram()
            project.file("out.txt", "one\ntwo\n")
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project)
            openRunWindow()
            addConfiguration("Program", emit("out.txt"))
            pressRun()
            awaitRunStatus(app, RunStatus.Succeeded)
            before.assertUnchanged(project)
        }
}
