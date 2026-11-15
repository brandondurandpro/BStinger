package org.example.kotlineditor.unit

import org.example.kotlineditor.state.SwapJob
import org.example.kotlineditor.state.UpdateOs
import org.example.kotlineditor.state.UpdateSwap
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PAYLOAD = "payload.txt"
private const val RELAUNCHED = "relaunched.txt"
private const val APP_NAME = "Probe"
private val windows = UpdateOs.fromName(System.getProperty("os.name")) == UpdateOs.WINDOWS

// The root of the app image a launcher of jpackage belongs to: `<root>/bin/<name>` on Linux, `<root>/<name>.exe`.
private fun rootOf(launcher: File): File = if (windows) launcher.parentFile else launcher.parentFile.parentFile

/**
 * The program of the packaged probe. Started with the folders of an update, it asks [UpdateSwap] to install it and
 * exits, as the IDE does when its last window has closed; started again without argument (by the script, once
 * swapped), it writes which version it is next to the install.
 */
object PackagedRestartProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val launcher = File(System.getProperty("jpackage.app-path"))
        val root = rootOf(launcher)
        if (args.isEmpty()) {
            File(root.parentFile, RELAUNCHED).writeText(File(root, PAYLOAD).readText())
            return
        }
        val pid = ProcessHandle.current().pid()
        val job = SwapJob(pid, root, File(args[1]), File(args[2]), listOf(launcher.path), File(args[0]))
        UpdateSwap.start(job)
    }
}

/**
 * The restart of an update with a real launcher of jpackage, which no fake launcher stands for: the script started
 * by the packaged program must outlive it (on Windows the launcher runs the JVM in a job object), swap the app image
 * the launcher was started from, and start the new launcher, which must not take the environment its predecessor
 * left (`_JPACKAGE_LAUNCHER`) for its own. Skipped when the JDK of the tests has no jpackage.
 */
class PackagedRestartTest {
    private val work: File = Files.createTempDirectory("packaged restart").toFile()

    @AfterTest
    fun cleanup() {
        work.deleteRecursively()
    }

    @Test
    fun `a packaged program that exits is swapped by the script it started, and the new one is started`() {
        val jpackage = File(System.getProperty("java.home"), if (windows) "bin/jpackage.exe" else "bin/jpackage")
        if (!jpackage.isFile) {
            println("PackagedRestartTest skipped: no jpackage in ${System.getProperty("java.home")}")
            return
        }
        val root = packageProbe(jpackage)
        File(root, PAYLOAD).writeText("old")
        val staged = File(work, "$APP_NAME.update")
        copyTree(root, staged)
        File(staged, PAYLOAD).writeText("new")
        val backup = File(work, "$APP_NAME.old")
        val updates = File(work, "updates")
        val launcher = File(root, if (windows) "$APP_NAME.exe" else "bin/$APP_NAME")

        val started = run(listOf(launcher.path, updates.path, staged.path, backup.path))
        assertEquals(0, started, "the packaged program starts the script and exits")
        val relaunched = File(work, RELAUNCHED)
        val log = File(updates, UpdateSwap.LOG_NAME)
        awaitFile(relaunched) { "the new launcher was not started; log: ${log.takeIf(File::isFile)?.readText()}" }
        assertEquals("new", relaunched.readText(), "the launcher started is the one of the new version")
        assertEquals("new", File(root, PAYLOAD).readText())
        assertFalse(staged.exists())
        awaitGone(backup)
        assertTrue("updated" in log.readText(), log.readText())
    }

    // The app image of the probe: the jars of its classes and of the Kotlin library, on the runtime of the tests.
    private fun packageProbe(jpackage: File): File {
        val input = File(work, "input").apply { mkdirs() }
        val probeJar = jarOf(PackagedRestartProbe::class.java, input)
        jarOf(UpdateSwap::class.java, input)
        jarOf(Unit::class.java, input)
        val command =
            listOf(
                jpackage.path, "--type", "app-image", "--dest", work.path, "--name", APP_NAME,
                "--input", input.path, "--main-jar", probeJar.name,
                "--main-class", PackagedRestartProbe::class.java.name,
                "--runtime-image", System.getProperty("java.home"),
            )
        assertEquals(0, run(command), "jpackage builds the probe")
        return File(work, APP_NAME)
    }

    // The jar that holds [type] in [input]: its own jar copied, or its directory of classes packed.
    private fun jarOf(type: Class<*>, input: File): File {
        val location = type.protectionDomain.codeSource.location
        val source = File(location.toURI())
        val count = input.list()?.size ?: 0
        val jar = File(input, "$count-${source.nameWithoutExtension}.jar")
        if (source.isFile) source.copyTo(jar) else packDirectory(source, jar)
        return jar
    }

    private fun packDirectory(directory: File, jar: File) {
        JarOutputStream(jar.outputStream()).use { output ->
            for (file in directory.walkTopDown().filter(File::isFile)) {
                output.putNextEntry(JarEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }

    // A copy that keeps what a runtime needs to run: the executable bits on Unix.
    private fun copyTree(from: File, to: File) {
        if (windows) {
            assertTrue(from.copyRecursively(to))
        } else {
            assertEquals(0, run(listOf("cp", "-a", from.path, to.path)))
        }
    }

    private fun run(command: List<String>): Int {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(RUN_SECONDS, TimeUnit.SECONDS), "${command.first()} did not end: $output")
        if (process.exitValue() != 0) println(output)
        return process.exitValue()
    }

    private fun awaitFile(file: File, message: () -> String) {
        await({ file.isFile && file.length() > 0 }, message)
    }

    private fun awaitGone(file: File) {
        await({ !file.exists() }) { "$file is still there" }
    }

    private fun await(done: () -> Boolean, message: () -> String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!done()) {
            assertTrue(System.nanoTime() < deadline, message())
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
        }
    }

    private companion object {
        const val RUN_SECONDS = 300L
        const val WAIT_SECONDS = 90L
        const val POLL_MILLIS = 100L
    }
}
