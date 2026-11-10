package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.RunTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TICKS = 25
private const val PAUSE_MS = 120

/** Commands running on the server while the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class RunChaosE2eTest {
    private val project = SampleProject.standard().withEmitProgram()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun runs(): Int {
        val marker = project.root.resolve("ran.log")
        return if (Files.exists(marker)) Files.readAllLines(marker).size else 0
    }

    private fun ticks(count: Int): List<String> = (1..count).map { "tick $it" }

    @Test
    fun `a command started while the network is down runs once it is back, and only once`() =
        runComposeUiTest {
            project.file("out.txt", "ran\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Marked", emit("out.txt", mode = "mark:ran.log"))
            app.network.goDown()
            awaitReconnecting()
            pressRun()
            app.network.comeBack()
            awaitRunStatus(app, RunStatus.Succeeded)
            awaitReconnected()
            assertEquals(1, runs())
            assertEquals(listOf("ran"), app.runOutput())
        }

    @Test
    fun `a command whose answer is lost is not run a second time`() =
        runComposeUiTest {
            project.file("out.txt", "ran\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Marked", emit("out.txt", mode = "mark:ran.log"))
            // The command starts, then the connection breaks before the answer: the client asks again.
            app.network.dropFromServer = true
            pressRun()
            awaitCondition { runs() == 1 }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitRunStatus(app, RunStatus.Succeeded)
            awaitReconnected()
            assertEquals(1, runs())
            assertEquals(listOf("ran"), app.runOutput())
        }

    @Test
    fun `the output of a command survives a connection cut, each line once and in order`() =
        runComposeUiTest {
            project.file("out.txt", "begin\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Slow", emit("out.txt", mode = "ticks:$TICKS:$PAUSE_MS"))
            pressRun()
            awaitCondition { app.runOutput().size > 3 }
            app.network.cutAll()
            awaitRunStatus(app, RunStatus.Succeeded)
            assertEquals(listOf("begin") + ticks(TICKS), app.runOutput())
        }

    @Test
    fun `the output of a command survives a network that goes down for a while`() =
        runComposeUiTest {
            project.file("out.txt", "begin\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Slow", emit("out.txt", mode = "ticks:$TICKS:$PAUSE_MS"))
            pressRun()
            awaitCondition { app.runOutput().size > 3 }
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitRunStatus(app, RunStatus.Succeeded)
            awaitReconnected()
            assertEquals(listOf("begin") + ticks(TICKS), app.runOutput())
        }

    @Test
    fun `a link that goes silent is noticed, and the output goes on after it`() =
        runComposeUiTest {
            project.file("out.txt", "begin\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Slow", emit("out.txt", mode = "ticks:$TICKS:$PAUSE_MS"))
            pressRun()
            awaitCondition { app.runOutput().size > 3 }
            app.network.blackHole = true
            awaitReconnecting()
            app.network.blackHole = false
            awaitRunStatus(app, RunStatus.Succeeded)
            assertEquals(listOf("begin") + ticks(TICKS), app.runOutput())
        }

    @Test
    fun `a command lost in a server restart says so, and a new run works`() =
        runComposeUiTest {
            project.file("out.txt", "up\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            pressRun()
            awaitCondition { app.runOutput() == listOf("up") }
            app.process.restart()
            awaitReconnected()
            awaitRunStatus(app, RunStatus.Lost)
            onNodeWithTag(RunTags.STATUS).assertTextEquals("Lost: the server restarted")
            project.file("out.txt", "again\n")
            pressRun()
            awaitCondition { app.runOutput() == listOf("again") }
            onNodeWithTag(RunTags.STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
        }

    @Test
    fun `a slow link keeps the window responsive`() =
        runComposeUiTest {
            project.file("out.txt", "up\n")
            val app = launchWithProject(project, chaos = true)
            openRunWindow()
            addConfiguration("Hang", emit("out.txt", mode = "hang"))
            app.network.latencyMs = 150
            pressRun()
            awaitCondition { app.runOutput() == listOf("up") }
            onNodeWithTag(RunTags.STOP).performClick()
            awaitRunStatus(app, RunStatus.Stopped)
            app.network.latencyMs = 0
        }
}
