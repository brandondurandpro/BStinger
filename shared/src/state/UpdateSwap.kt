package org.example.kotlineditor.state

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** What the helper does once the IDE has exited: replace [installed] by [staged], then start [launcher]. */
data class SwapJob(
    /** The process to wait for: the IDE. */
    val pid: Long,
    val installed: File,
    val staged: File,
    /** Where the old install waits during the swap, deleted at the end. */
    val backup: File,
    val launcher: List<String>,
    /** The helper writes its script and its log here, outside the install. */
    val directory: File,
    /** How long the helper waits for the IDE to exit before giving up and doing nothing. */
    val waitSeconds: Int = DEFAULT_WAIT_SECONDS,
) {
    companion object {
        const val DEFAULT_WAIT_SECONDS = 120

        /** The job that replaces the install of [target], once the process [pid] has exited. */
        fun forTarget(target: UpdateTarget, pid: Long, directory: File): SwapJob =
            SwapJob(pid, target.installed, target.staging, target.backup, target.launcher, directory)
    }
}

/**
 * The detached script that installs a staged update once the IDE has exited: a running install is never swapped.
 * It waits for the IDE's process to end (and gives up after [SwapJob.waitSeconds], touching nothing), moves the old
 * install to a backup name, moves the staged one into place (the backup comes back if that fails), starts the IDE
 * again, then deletes the backup and itself. Whatever happens is appended to `update.log` next to it. When the
 * staged version is missing or the swap fails, the old IDE is started again.
 *
 * Unix: POSIX `/bin/sh` (it runs under dash). Windows: `cmd.exe` batch, with no PowerShell and no `timeout` (it fails
 * without a console): the waits are `ping`s.
 */
object UpdateSwap {
    const val LOG_NAME = "update.log"
    private const val UNIX_SCRIPT = "apply-update.sh"
    private const val WINDOWS_SCRIPT = "apply-update.cmd"

    // What a launcher of the IDE must not hand down to the new one: options meant for the JVM that ran the update,
    // the variables of the AppImage whose mount is gone, and the mark the launcher of jpackage leaves for itself
    // (`_JPACKAGE_LAUNCHER`: a launcher that finds it takes itself for its own restart, reads no option of its cfg
    // file and starts a JVM that has nothing to run).
    private val INHERITED_BROKEN =
        listOf(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JPACKAGE_LAUNCHER",
            "APPDIR",
            "APPIMAGE",
            "ARGV0",
            "OWD",
        )

    /** The script of [job] for [os]. */
    fun script(job: SwapJob, os: UpdateOs): String =
        if (os == UpdateOs.WINDOWS) WindowsSwapScript.build(job) else UnixSwapScript.build(job)

    /** The environment [inherited] from the IDE, without what the new IDE must not inherit. */
    fun cleanEnvironment(inherited: Map<String, String>): Map<String, String> =
        inherited.filterKeys { name -> INHERITED_BROKEN.none { it.equals(name, ignoreCase = true) } }

    /**
     * Writes the script of [job] and starts it detached: it outlives the IDE. The script and the IDE it starts again
     * get the [inherited] environment of this one, cleaned by [cleanEnvironment]. Returns the process.
     */
    fun start(
        job: SwapJob,
        os: UpdateOs = UpdateOs.fromName(System.getProperty("os.name")),
        inherited: Map<String, String> = System.getenv(),
    ): Process {
        Files.createDirectories(job.directory.toPath())
        val windows = os == UpdateOs.WINDOWS
        val file = File(job.directory, if (windows) WINDOWS_SCRIPT else UNIX_SCRIPT)
        file.writeText(script(job, os))
        val command = if (windows) listOf("cmd.exe", "/c", file.name) else listOf("/bin/sh", file.path)
        val builder = ProcessBuilder(command).directory(job.directory)
        builder.environment().clear()
        builder.environment().putAll(cleanEnvironment(inherited))
        builder.redirectErrorStream(true).redirectOutput(outputOf(job, windows))
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
        return try {
            builder.start()
        } catch (failure: IOException) {
            throw IOException("Cannot start the installer of the update: ${failure.message}", failure)
        }
    }

    // What the commands of the script print goes to the log on Unix. Not on Windows: a file this process holds open
    // for writing cannot be opened by the `>>` of the batch file, whose every line of log (and every command that
    // logs its errors) would then fail; the script writes its log alone.
    private fun outputOf(job: SwapJob, windows: Boolean): ProcessBuilder.Redirect {
        if (windows) return ProcessBuilder.Redirect.DISCARD
        return ProcessBuilder.Redirect.appendTo(File(job.directory, LOG_NAME))
    }

    private fun nullDevice(): File = File(if (File("/dev/null").exists()) "/dev/null" else "NUL")
}
