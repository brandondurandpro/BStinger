package org.example.kotlineditor.unit

import org.example.kotlineditor.e2e.FakeInstall
import org.example.kotlineditor.e2e.FakeRelease
import org.example.kotlineditor.e2e.updateTestOs
import org.example.kotlineditor.state.SwapJob
import org.example.kotlineditor.state.UpdateOs
import org.example.kotlineditor.state.UpdateSwap
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The script that installs an update after the IDE has exited, really executed: `sh` (dash on Ubuntu) on Linux,
 * `cmd.exe` on Windows, against a fake install in a temporary directory, a short-lived process standing for the IDE,
 * and a launcher that writes a marker file when it is started. The folders have spaces and shell characters.
 */
class UpdateSwapTest {
    private val windows = updateTestOs == UpdateOs.WINDOWS
    private val base: File = tempDirectory()
    private val processes = ArrayList<Process>()
    private val installs = ArrayList<FakeInstall>()

    @AfterTest
    fun cleanup() {
        processes.forEach(Process::destroyForcibly)
        base.deleteRecursively()
        installs.forEach(FakeInstall::close)
    }

    // Spaces and the characters a shell or a batch file treats specially.
    private fun tempDirectory(): File {
        val name = if (windows) "swap & (100%) test" else "swap it's \$x (test)"
        return Files.createTempDirectory(name).toFile()
    }

    private class Fixture(val install: FakeInstall, val staged: File, val backup: File, val directory: File) {
        val job: SwapJob get() = job(0)

        fun job(pid: Long, waitSeconds: Int = 60): SwapJob =
            SwapJob(pid, install.root, staged, backup, listOf(install.launcher.path), directory, waitSeconds)
    }

    private fun fixture(stagedExists: Boolean = true, backupInside: String = ""): Fixture {
        val install = FakeInstall.create()
        installs.add(install)
        val staged = File(base, "staged tree")
        if (stagedExists) FakeRelease.writeTree(staged, "new")
        val backup = File(File(base, backupInside), "backup tree")
        return Fixture(install, staged, backup, File(base, "updates dir"))
    }

    // A process that lives about two seconds, standing for the IDE.
    private fun shortLivedIde(): Process = ide(if (windows) "ping -n 3 127.0.0.1 >nul" else "sleep 2")

    private fun longLivedIde(): Process = ide(if (windows) "ping -n 120 127.0.0.1 >nul" else "sleep 120")

    // A process that has already exited: its pid is free, nothing to wait for.
    private fun exitedIde(): Long {
        val process = ide("exit 0")
        process.waitFor()
        return process.pid()
    }

    private fun ide(command: String): Process {
        val line = if (windows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)
        val process = ProcessBuilder(line).redirectErrorStream(true).start()
        processes.add(process)
        return process
    }

    private fun runHelper(job: SwapJob, inherited: Map<String, String> = System.getenv()) {
        val helper = UpdateSwap.start(job, inherited = inherited)
        processes.add(helper)
        assertTrue(helper.waitFor(HELPER_SECONDS, TimeUnit.SECONDS), "the helper did not end")
    }

    private fun awaitFile(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HELPER_SECONDS)
        while (!file.exists() && System.nanoTime() < deadline) LockSupport.parkNanos(POLL_NANOS)
        assertTrue(file.exists(), "$file never appeared")
    }

    private fun log(fixture: Fixture): String =
        File(fixture.directory, UpdateSwap.LOG_NAME).takeIf { it.isFile }?.readText().orEmpty()

    private fun scripts(fixture: Fixture): List<String> =
        fixture.directory
            .list { _, name -> name.startsWith("apply-update") }
            ?.toList()
            .orEmpty()

    @Test
    fun `the install is swapped only after the IDE exited, the new launcher starts, the backup and the script go`() {
        val fixture = fixture()
        val ide = shortLivedIde()
        val helper =
            UpdateSwap.start(
                fixture.job(ide.pid()),
                inherited = System.getenv() + ("JAVA_TOOL_OPTIONS" to "-Dbroken=1"),
            )
        processes.add(helper)
        // The IDE still runs: nothing is touched.
        assertTrue(ide.isAlive)
        assertEquals("old", fixture.install.version())
        assertTrue(helper.waitFor(HELPER_SECONDS, TimeUnit.SECONDS))
        assertFalse(ide.isAlive)
        assertEquals("new", fixture.install.version())
        awaitFile(fixture.install.launchMarker)
        val marker = fixture.install.launchMarker.readText()
        assertFalse("broken" in marker, "the new IDE inherited the options of the old one: $marker")
        assertFalse(fixture.staged.exists())
        assertFalse(fixture.backup.exists(), "the backup is deleted at the end")
        assertEquals(emptyList(), scripts(fixture), "the script deletes itself")
        assertTrue("updated" in log(fixture), log(fixture))
    }

    @Test
    fun `without the staged version nothing is moved and the old IDE starts again`() {
        val fixture = fixture(stagedExists = false)
        runHelper(fixture.job(exitedIde()))
        assertEquals("old", fixture.install.version())
        assertFalse(fixture.backup.exists())
        awaitFile(fixture.install.launchMarker)
        assertTrue("nothing to install" in log(fixture), log(fixture))
        assertEquals(emptyList(), scripts(fixture))
    }

    @Test
    fun `when the old install cannot be moved aside it stays in place and starts again`() {
        val fixture = fixture(backupInside = "no such folder")
        runHelper(fixture.job(exitedIde()))
        assertEquals("old", fixture.install.version())
        assertTrue(fixture.staged.exists(), "the staged version is kept")
        awaitFile(fixture.install.launchMarker)
        assertTrue("cannot move" in log(fixture), log(fixture))
    }

    @Test
    fun `when the new version cannot be moved into place the backup comes back`() {
        // A staged tree inside a read-only folder cannot be moved out of it. Not built on Windows (a folder with an
        // open file) nor as root (nothing is read-only).
        if (windows) return
        val fixture = fixture(stagedExists = false)
        val locked = File(base, "locked")
        val staged = File(locked, "staged tree")
        FakeRelease.writeTree(staged, "new")
        locked.setWritable(false)
        try {
            if (Files.isWritable(locked.toPath())) return
            val job = fixture.job(exitedIde()).copy(staged = staged)
            runHelper(job)
            assertEquals("old", fixture.install.version(), "the old install is put back")
            assertFalse(fixture.backup.exists())
            awaitFile(fixture.install.launchMarker)
            assertTrue("the old one is back" in log(fixture), log(fixture))
        } finally {
            locked.setWritable(true)
        }
    }

    @Test
    fun `a single file install, an AppImage, is replaced the same way and the new file is started`() {
        // AppImages are Linux files; the batch script only moves directories.
        if (windows) return
        val dir = File(base, "apps").apply { mkdirs() }
        val marker = File(dir, "marker.txt")
        val installed = File(dir, "kotlin-editor.AppImage")
        val staged = File(dir, "kotlin-editor.AppImage.update")
        val backup = File(dir, "kotlin-editor.AppImage.old")
        installed.writeText(appImageScript("old"))
        staged.writeText(appImageScript("new"))
        installed.setExecutable(true)
        staged.setExecutable(true)
        val job = SwapJob(exitedIde(), installed, staged, backup, listOf(installed.path), File(base, "updates dir"))
        runHelper(job)
        awaitFile(marker)
        awaitUntilText(marker, "new")
        assertFalse(staged.exists())
        assertFalse(backup.exists())
    }

    // A stand-in AppImage: writes [text] in a marker beside itself (the folder name has quotes: none in the script).
    private fun appImageScript(text: String): String =
        """
        #!/bin/sh
        echo $text > "${'$'}(dirname "${'$'}0")/marker.txt"
        """.trimIndent() + "\n"

    // The marker is written by a process started by the helper: it appears, then holds its text.
    private fun awaitUntilText(file: File, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HELPER_SECONDS)
        while (file.readText().trim() != text && System.nanoTime() < deadline) LockSupport.parkNanos(POLL_NANOS)
        assertEquals(text, file.readText().trim())
    }

    @Test
    fun `an IDE that never exits leaves everything as it is after the wait`() {
        val fixture = fixture()
        val ide = longLivedIde()
        runHelper(fixture.job(ide.pid(), waitSeconds = 1))
        assertTrue(ide.isAlive)
        assertEquals("old", fixture.install.version())
        assertTrue(fixture.staged.exists())
        assertFalse(fixture.backup.exists())
        assertFalse(fixture.install.launchMarker.exists(), "nothing is started while the IDE still runs")
        assertTrue("still running" in log(fixture), log(fixture))
    }

    @Test
    fun `the options of the old JVM, the mark of its launcher and the AppImage variables are not handed down`() {
        val inherited =
            mapOf(
                "PATH" to "/bin",
                "JAVA_TOOL_OPTIONS" to "-javaagent:x",
                "_JAVA_OPTIONS" to "-Xmx1m",
                "APPDIR" to "/mnt",
                // Left by the launcher of jpackage: a new launcher that inherits it starts a JVM with nothing to run.
                "_JPACKAGE_LAUNCHER" to "4242",
                "KEEP" to "yes",
            )
        assertEquals(mapOf("PATH" to "/bin", "KEEP" to "yes"), UpdateSwap.cleanEnvironment(inherited))
    }

    @Test
    fun `the scripts quote every path`() {
        val fixture = fixture()
        val unix = UpdateSwap.script(fixture.job, UpdateOs.LINUX)
        assertTrue(unix.startsWith("#!/bin/sh"))
        assertTrue("installed='${fixture.install.root.path.replace("'", "'\\''")}'" in unix, unix)
        val batch = UpdateSwap.script(fixture.job, UpdateOs.WINDOWS)
        assertTrue("set \"INSTALLED=${fixture.install.root.path.replace("%", "%%")}\"" in batch, batch)
        assertTrue("\r\n" in batch)
    }

    private companion object {
        const val HELPER_SECONDS = 90L
        const val POLL_NANOS = 20_000_000L
    }
}
