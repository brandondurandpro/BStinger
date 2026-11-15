package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Where the `ide` command of the shell stands. */
enum class CommandLineStatus {
    /** The IDE does not know where its launcher is (a run from the sources): it cannot write the command. */
    Unavailable,
    NotInstalled,
    Installed,

    /** Ours, but it starts another launcher (the app moved, or a copy of it ran): Install rewrites it. */
    Outdated,

    /** Another program named `ide` is in the folder: it is never overwritten. */
    Foreign,
}

/**
 * The `ide [folder]` command of the shell, installed by the user from the Settings: a small script in [binDir] (a
 * folder of the user, no administrator rights) that starts the [launcher] of the installed app with `--open`.
 */
@Stable
class CommandLineTool(
    private val flavor: ShellFlavor,
    private val launcher: Path?,
    private val binDir: Path,
    private val userPath: UserPath,
) {
    var status by mutableStateOf(CommandLineStatus.Unavailable)
        private set

    /** What the user has to know after an action: a failure, or the line to add to the PATH. */
    var message: String? by mutableStateOf(null)
        private set

    /** Where the command is, or would be written. */
    val scriptPath: Path = binDir.resolve(if (flavor == ShellFlavor.Windows) "ide.cmd" else "ide")

    init {
        refresh()
    }

    /** Reads the disk again. Nothing is written: a copy of the app that is only run once must not take the command. */
    fun refresh() {
        val target = launcher
        if (target == null) {
            status = CommandLineStatus.Unavailable
            return
        }
        val existing = readOrNull(scriptPath)
        val current = existing == scriptFor(target)
        status =
            when {
                existing == null -> CommandLineStatus.NotInstalled
                SCRIPT_MARKER !in existing -> CommandLineStatus.Foreign
                current -> CommandLineStatus.Installed
                else -> CommandLineStatus.Outdated
            }
    }

    fun install() {
        val target = launcher ?: return
        if (status == CommandLineStatus.Foreign || status == CommandLineStatus.Installed) return
        message =
            try {
                write(target)
                pathAdvice()
            } catch (e: IOException) {
                "Cannot install the command: ${e.message}"
            }
        refresh()
    }

    fun remove() {
        val ours = status == CommandLineStatus.Installed || status == CommandLineStatus.Outdated
        if (!ours) return
        message =
            try {
                Files.deleteIfExists(scriptPath)
                twinOf()?.let { twin -> deleteIfOurs(twin) }
                null
            } catch (e: IOException) {
                "Cannot remove the command: ${e.message}"
            }
        refresh()
    }

    private fun write(target: Path) {
        Files.createDirectories(binDir)
        writeScript(scriptPath, scriptFor(target))
        val twin = twinOf()
        if (twin != null && !isForeign(twin)) writeScript(twin, CommandScripts.windowsGitBash(target))
    }

    private fun scriptFor(target: Path): String =
        if (flavor == ShellFlavor.Windows) CommandScripts.windowsCmd(target) else CommandScripts.posix(flavor, target)

    // On Windows `ide.cmd` has an `ide` next to it, for Git Bash.
    private fun twinOf(): Path? = if (flavor == ShellFlavor.Windows) binDir.resolve("ide") else null

    private fun isForeign(file: Path): Boolean {
        val text = readOrNull(file) ?: return false
        return SCRIPT_MARKER !in text
    }

    private fun deleteIfOurs(file: Path) {
        if (!isForeign(file)) Files.deleteIfExists(file)
    }

    // Written aside then moved: the command a shell is running is never cut in two.
    private fun writeScript(file: Path, text: String) {
        val temporary = Files.createTempFile(binDir, ".ide", ".tmp")
        try {
            Files.writeString(temporary, text)
            if (flavor != ShellFlavor.Windows) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    // Windows: the folder goes on the PATH of the user. Elsewhere the user is told when the shell cannot find it.
    private fun pathAdvice(): String? {
        if (flavor == ShellFlavor.Windows) userPath.add(binDir)
        val onPath = flavor == ShellFlavor.Windows || userPath.contains(binDir)
        return when {
            flavor == ShellFlavor.Windows -> "Open a new terminal to use it."
            onPath -> null
            else -> "If your shell does not find `ide`, add this line to its profile: export PATH=\"$binDir:\$PATH\""
        }
    }

    private fun readOrNull(file: Path): String? =
        runCatching { if (Files.isRegularFile(file)) Files.readString(file) else null }.getOrNull()

    companion object {
        /** The command of this machine: in `~/.local/bin`, or in `%LOCALAPPDATA%\kotlin-editor\bin` on Windows. */
        fun forThisMachine(launcher: Path?): CommandLineTool {
            val flavor = ShellFlavor.current()
            val home = Path.of(System.getProperty("user.home"))
            if (flavor != ShellFlavor.Windows) {
                return CommandLineTool(flavor, launcher, home.resolve(".local/bin"), ShellUserPath.ofEnvironment())
            }
            val appData = System.getenv("LOCALAPPDATA")?.let { Path.of(it) } ?: home.resolve("AppData/Local")
            return CommandLineTool(flavor, launcher, appData.resolve("kotlin-editor/bin"), WindowsUserPath())
        }

        /** No launcher is known: the setting says so. */
        fun unavailable(): CommandLineTool {
            val nowhere = Path.of(System.getProperty("java.io.tmpdir"))
            return CommandLineTool(ShellFlavor.current(), null, nowhere, ShellUserPath(emptyList()))
        }
    }
}
