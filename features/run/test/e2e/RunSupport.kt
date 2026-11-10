package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RunTags
import java.nio.file.Path

// The java of the JVM running the tests: the one program every platform of the CI has (no shell, no POSIX command).
private val JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString()

// A program the tests run through `java Emit.java <file> <exit code> [mode]` (a single source file, no build): it
// prints the lines of <file>, then ends with the exit code. The mode `hang` makes it wait for ever instead,
// `ticks:<count>:<pause in ms>` makes it print "tick <n>" lines slowly, and `mark:<file>` appends a line to <file>
// when it starts, so that a test can count how many times it ran.
private val EMIT_SOURCE =
    """
    import java.nio.file.*;
    import java.util.*;

    public class Emit {
        public static void main(String[] args) throws Exception {
            for (String line : Files.readAllLines(Path.of(args[0]))) System.out.println(line);
            System.out.flush();
            String mode = args.length > 2 ? args[2] : "";
            if (mode.startsWith("mark:")) {
                Files.writeString(Path.of(mode.substring(5)), "ran\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (mode.equals("hang")) Thread.sleep(600000);
            if (mode.startsWith("ticks:")) {
                String[] parts = mode.split(":");
                for (int i = 1; i <= Integer.parseInt(parts[1]); i++) {
                    System.out.println("tick " + i);
                    System.out.flush();
                    Thread.sleep(Long.parseLong(parts[2]));
                }
            }
            System.exit(Integer.parseInt(args[1]));
        }
    }
    """.trimIndent()

/** Adds `Emit.java` to the project: the program [emit] runs. */
fun SampleProject.withEmitProgram(): SampleProject = apply { file("Emit.java", EMIT_SOURCE) }

/** The command line that prints the lines of [file] (a file of the project), then exits with [exitCode]. */
fun emit(file: String, exitCode: Int = 0, mode: String = ""): String =
    "\"$JAVA\" Emit.java $file $exitCode $mode".trim()

/** Shows the Run tool window from the strip, as a user would. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openRunWindow() {
    onNodeWithTag(AppTags.TOOL_RUN).performClick()
    awaitTag(RunTags.RUN)
}

/** Creates a configuration in the editor of the Run window, and saves it: it becomes the selected one. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.addConfiguration(name: String, command: String, kind: RunKind = RunKind.Run) {
    awaitTag(RunTags.SELECTOR).performClick()
    awaitTag(RunTags.NEW).performClick()
    awaitTag(RunTags.EDITOR_NAME).performTextInput(name)
    onNodeWithTag(RunTags.EDITOR_COMMAND).performTextInput(command)
    if (kind == RunKind.Tests) onNodeWithTag(RunTags.EDITOR_KIND_TESTS).performClick()
    onNodeWithTag(RunTags.EDITOR_SAVE).performClick()
    awaitNoTag(RunTags.EDITOR)
}

/** Presses the Run button. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pressRun() {
    onNodeWithTag(RunTags.START).performClick()
}

/** Waits until the run shown has [status]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitRunStatus(app: E2eApp, status: RunStatus) {
    awaitCondition {
        app.project.run.session
            ?.status == status
    }
}

// The notice a JVM prints on its error output when JAVA_TOOL_OPTIONS is set, as on the CI: the first lines of the
// output of every run of `emit`, before the program prints anything.
private const val JAVA_NOTICE = "Picked up "

private fun String.isJavaNotice(): Boolean = startsWith(JAVA_NOTICE)

private fun E2eApp.allOutput(): List<String> =
    project.run.session
        ?.lines
        ?.toList()
        .orEmpty()

/** The lines of output of the run shown, without the notices of the JVM. */
fun E2eApp.runOutput(): List<String> = allOutput().filterNot { it.isJavaNotice() }

/** How many notices of the JVM come before the output of the program in the run shown. */
fun E2eApp.javaNotices(): Int = allOutput().count { it.isJavaNotice() }

/** The line [index] of the output of the program (the notices of the JVM do not count), once it is shown. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitOutputLine(app: E2eApp, index: Int): SemanticsNodeInteraction {
    awaitCondition { app.runOutput().size > index }
    return awaitTag(RunTags.line(app.javaNotices() + index))
}

/** The link [number] of the line [line] of the output of the program, once it is shown. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitOutputLink(app: E2eApp, line: Int, number: Int = 0): SemanticsNodeInteraction {
    awaitCondition { app.runOutput().size > line }
    return awaitTag(RunTags.link(app.javaNotices() + line, number))
}
